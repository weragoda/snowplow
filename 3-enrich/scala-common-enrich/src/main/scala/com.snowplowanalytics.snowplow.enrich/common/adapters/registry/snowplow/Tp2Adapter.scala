/*
 * Copyright (c) 2012-2019 Snowplow Analytics Ltd. All rights reserved.
 *
 * This program is licensed to you under the Apache License Version 2.0,
 * and you may not use this file except in compliance with the Apache License Version 2.0.
 * You may obtain a copy of the Apache License Version 2.0 at http://www.apache.org/licenses/LICENSE-2.0.
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the Apache License Version 2.0 is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the Apache License Version 2.0 for the specific language governing permissions and limitations there under.
 */
package com.snowplowanalytics.snowplow.enrich.common
package adapters
package registry
package snowplow

import java.util.Map.{Entry => JMapEntry}

import scala.collection.JavaConversions._

import cats.data.{NonEmptyList, Validated, ValidatedNel}
import cats.data.Validated._
import cats.syntax.either._
import cats.syntax.validated._
import com.snowplowanalytics.iglu.client.{Resolver, SchemaCriterion}
import com.snowplowanalytics.iglu.client.validation.ValidatableJsonMethods._
import com.fasterxml.jackson.databind.JsonNode

import loaders.CollectorPayload
import utils.{JsonUtils => JU}

/**
 * Version 2 of the Tracker Protocol supports GET and POST. Note that with POST, data can still be
 * passed on the querystring.
 */
object Tp2Adapter extends Adapter {
  // Expected content types for a request body
  private object ContentTypes {
    val list =
      List("application/json", "application/json; charset=utf-8", "application/json; charset=UTF-8")
    val str = list.mkString(", ")
  }

  // Request body expected to validate against this JSON Schema
  private val PayloadDataSchema =
    SchemaCriterion("com.snowplowanalytics.snowplow", "payload_data", "jsonschema", 1, 0)

  /**
   * Converts a CollectorPayload instance into N raw events.
   * @param payload The CollectorPaylod containing one or more raw events
   * @param resolver (implicit) The Iglu resolver used for schema lookup and validation
   * @return a Validation boxing either a NEL of RawEvents on Success, or a NEL of Failure Strings
   */
  def toRawEvents(payload: CollectorPayload)(implicit resolver: Resolver): ValidatedRawEvents = {
    val qsParams = toMap(payload.querystring)

    // Verify: body + content type set; content type matches expected; body contains expected JSON Schema; body passes schema validation
    val validatedParamsNel: ValidatedNel[String, NonEmptyList[RawEventParameters]] =
      (payload.body, payload.contentType) match {
        case (None, _) if qsParams.isEmpty =>
          s"Request body and querystring parameters empty, expected at least one populated".invalidNel
        case (_, Some(ct)) if !ContentTypes.list.contains(ct) =>
          s"Content type of ${ct} provided, expected one of: ${ContentTypes.str}".invalidNel
        case (Some(_), None) =>
          s"Request body provided but content type empty, expected one of: ${ContentTypes.str}".invalidNel
        case (None, Some(ct)) => s"Content type of ${ct} provided but request body empty".invalidNel
        case (None, None) => NonEmptyList.one(qsParams).valid
        case (Some(bdy), Some(_)) => // Build our NEL of parameters
          (for {
            json <- extractAndValidateJson("Body", PayloadDataSchema, bdy)
            nel <- toParametersNel(json, qsParams)
          } yield nel).toValidated
      }

    // Validated NEL of parameters -> Validated NEL of raw events
    for {
      paramsNel <- validatedParamsNel
    } yield
      for {
        params <- paramsNel
        p = payload // Alias to save typing
      } yield RawEvent(p.api, params, p.contentType, p.source, p.context)
  }

  /**
   * Converts a JSON Node into a Validated NEL of parameters for a RawEvent. The parameters
   * take the form Map[String, String].
   * Takes a second set of parameters to merge with the generated parameters (the second set takes
   * precedence in case of a clash).
   * @param instance The JSON Node to convert
   * @param mergeWith A second set of parameters to merge (and possibly overwrite) parameters
   * from the instance
   * @return a NEL of Map[String, String] parameters on Succeess, a NEL of Strings on Failure
   */
  private def toParametersNel(
    instance: JsonNode,
    mergeWith: RawEventParameters
  ): Either[NonEmptyList[String], NonEmptyList[RawEventParameters]] = {
    val events: List[List[Validated[String, (String, String)]]] = for {
      event <- instance.iterator.toList
    } yield
      for {
        entry <- event.fields.toList
      } yield toParameter(entry)

    val failures: List[String] = events.flatten.collect { case Invalid(f) => f }

    // We don't bother doing this conditionally because we
    // don't expect any failures, so any performance gain
    // from conditionality would be miniscule
    val successes: List[RawEventParameters] = (for {
      params <- events
    } yield
      (for {
        param <- params
      } yield param).collect {
        case Valid(p) => p
      }.toMap ++ mergeWith) // Overwrite with mergeWith

    (successes, failures) match {
      case (s :: ss, Nil) => NonEmptyList.of(s, ss: _*).asRight // No Failures collected
      case (s :: ss, f :: fs) =>
        // Some Failures, return those. Should never happen, unless JSON Schema changed
        NonEmptyList.of(f, fs: _*).asLeft
      case (Nil, _) =>
        NonEmptyList
          .one(
            "List of events is empty (should never happen, did JSON Schema change?)"
          )
          .asLeft
    }
  }

  /**
   * Converts a Java Map.Entry containing a JsonNode into a (String -> String) parameter.
   * @param entry The Java Map.Entry to convert
   * @return a Validation boxing either our parameter on Success, or an error String on Failure.
   */
  private def toParameter(
    entry: JMapEntry[String, JsonNode]): Validated[String, Tuple2[String, String]] = {
    val key = entry.getKey
    val rawValue = entry.getValue

    Option(rawValue.textValue) match {
      case Some(txt) => (key, txt).valid
      case None if rawValue.isTextual =>
        s"Value for key ${key} is a null String (should never happen, did Jackson implementation change?)".invalid
      case _ =>
        s"Value for key ${key} is not a String (should never happen, did JSON Schema change?)".invalid
    }
  }

  /**
   * Extract the JSON from a String, and validate it against the supplied JSON Schema.
   * @param field The name of the field containing the JSON instance
   * @param schemaCriterion The schema that we expected this self-describing JSON to conform to
   * @param instance A JSON instance as String
   * @param resolver Our implicit Iglu Resolver, for schema lookups
   * @return an Option-boxed Validation containing either a Nel of JsonNodes error message on
   * Failure, or a singular JsonNode on success
   */
  private def extractAndValidateJson(
    field: String,
    schemaCriterion: SchemaCriterion,
    instance: String
  )(implicit resolver: Resolver): Either[NonEmptyList[String], JsonNode] =
    for {
      j <- JU.extractJsonNode(field, instance).leftMap(NonEmptyList.one)
      v <- j
        .verifySchemaAndValidate(schemaCriterion, true)
        .leftMap(nel => NonEmptyList.of(nel.head, nel.tail: _*).map(_.getMessage))
        .toEither
    } yield v

}
