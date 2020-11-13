package org.thp.thehive.controllers.v0

import java.io.FilterInputStream
import java.nio.file.Files

import javax.inject.{Inject, Named, Singleton}
import net.lingala.zip4j.ZipFile
import net.lingala.zip4j.model.FileHeader
import org.thp.scalligraph._
import org.thp.scalligraph.auth.AuthContext
import org.thp.scalligraph.controllers._
import org.thp.scalligraph.models.{Database, UMapping}
import org.thp.scalligraph.query._
import org.thp.scalligraph.traversal.TraversalOps._
import org.thp.scalligraph.traversal.{Converter, IteratorOutput, Traversal}
import org.thp.thehive.controllers.v0.Conversion._
import org.thp.thehive.dto.v0.InputObservable
import org.thp.thehive.models._
import org.thp.thehive.services.CaseOps._
import org.thp.thehive.services.ObservableOps._
import org.thp.thehive.services.OrganisationOps._
import org.thp.thehive.services.ShareOps._
import org.thp.thehive.services.TagOps._
import org.thp.thehive.services._
import play.api.Configuration
import play.api.libs.Files.DefaultTemporaryFileCreator
import play.api.libs.json.{JsArray, JsObject, JsValue, Json}
import play.api.mvc.{Action, AnyContent, Results}

import scala.collection.JavaConverters._
import scala.util.Success

@Singleton
class ObservableCtrl @Inject() (
    configuration: Configuration,
    override val entrypoint: Entrypoint,
    @Named("with-thehive-schema") override val db: Database,
    observableSrv: ObservableSrv,
    observableTypeSrv: ObservableTypeSrv,
    caseSrv: CaseSrv,
    errorHandler: ErrorHandler,
    @Named("v0") override val queryExecutor: QueryExecutor,
    override val publicData: PublicObservable,
    temporaryFileCreator: DefaultTemporaryFileCreator
) extends ObservableRenderer
    with QueryCtrl {
  def create(caseId: String): Action[AnyContent] =
    entrypoint("create artifact")
      .extract("artifact", FieldsParser[InputObservable])
      .extract("isZip", FieldsParser.boolean.optional.on("isZip"))
      .extract("zipPassword", FieldsParser.string.optional.on("zipPassword"))
      .auth { implicit request =>
        val inputObservable: InputObservable = request.body("artifact")
        val isZip: Option[Boolean]           = request.body("isZip")
        val zipPassword: Option[String]      = request.body("zipPassword")
        val inputAttachObs                   = if (isZip.contains(true)) getZipFiles(inputObservable, zipPassword) else Seq(inputObservable)

        db
          .roTransaction { implicit graph =>
            for {
              case0 <-
                caseSrv
                  .get(EntityIdOrName(caseId))
                  .can(Permissions.manageObservable)
                  .orFail(AuthorizationError("Operation not permitted"))
              observableType <- observableTypeSrv.getOrFail(EntityName(inputObservable.dataType))
            } yield (case0, observableType)
          }
          .map {
            case (case0, observableType) =>
              val initialSuccessesAndFailures: (Seq[JsValue], Seq[JsValue]) =
                inputAttachObs.foldLeft[(Seq[JsValue], Seq[JsValue])](Nil -> Nil) {
                  case ((successes, failures), inputObservable) =>
                    inputObservable.attachment.fold((successes, failures)) { attachment =>
                      db
                        .tryTransaction { implicit graph =>
                          observableSrv
                            .create(inputObservable.toObservable, observableType, attachment, inputObservable.tags, Nil)
                            .flatMap(o => caseSrv.addObservable(case0, o).map(_ => o.toJson))
                        }
                        .fold(
                          e =>
                            successes -> (failures :+ errorHandler.toErrorResult(e)._2 ++ Json
                              .obj(
                                "object" -> Json
                                  .obj("data" -> s"file:${attachment.filename}", "attachment" -> Json.obj("name" -> attachment.filename))
                              )),
                          s => (successes :+ s) -> failures
                        )
                    }
                }

              val (successes, failures) = inputObservable
                .data
                .foldLeft(initialSuccessesAndFailures) {
                  case ((successes, failures), data) =>
                    db
                      .tryTransaction { implicit graph =>
                        observableSrv
                          .create(inputObservable.toObservable, observableType, data, inputObservable.tags, Nil)
                          .flatMap(o => caseSrv.addObservable(case0, o).map(_ => o.toJson))
                      }
                      .fold(
                        failure => (successes, failures :+ errorHandler.toErrorResult(failure)._2 ++ Json.obj("object" -> Json.obj("data" -> data))),
                        success => (successes :+ success, failures)
                      )
                }
              if (failures.isEmpty) Results.Created(JsArray(successes))
              else Results.MultiStatus(Json.obj("success" -> successes, "failure" -> failures))
          }
      }

  def get(observableId: String): Action[AnyContent] =
    entrypoint("get observable")
      .authRoTransaction(db) { implicit request => implicit graph =>
        observableSrv
          .get(EntityIdOrName(observableId))
          .visible
          .richObservable
          .getOrFail("Observable")
          .map { observable =>
            Results.Ok(observable.toJson)
          }
      }

  def update(observableId: String): Action[AnyContent] =
    entrypoint("update observable")
      .extract("observable", FieldsParser.update("observable", publicData.publicProperties))
      .authTransaction(db) { implicit request => implicit graph =>
        val propertyUpdaters: Seq[PropertyUpdater] = request.body("observable")
        observableSrv
          .update(
            _.get(EntityIdOrName(observableId)).can(Permissions.manageObservable),
            propertyUpdaters
          )
          .map(_ => Results.NoContent)
      }

  def findSimilar(observableId: String): Action[AnyContent] =
    entrypoint("find similar")
      .authRoTransaction(db) { implicit request => implicit graph =>
        val observables = observableSrv
          .get(EntityIdOrName(observableId))
          .visible
          .filteredSimilar
          .visible
          .richObservableWithCustomRenderer(observableLinkRenderer)
          .toSeq

        Success(Results.Ok(observables.toJson))
      }

  def bulkUpdate: Action[AnyContent] =
    entrypoint("bulk update")
      .extract("input", FieldsParser.update("observable", publicData.publicProperties))
      .extract("ids", FieldsParser.seq[String].on("ids"))
      .authTransaction(db) { implicit request => implicit graph =>
        val properties: Seq[PropertyUpdater] = request.body("input")
        val ids: Seq[String]                 = request.body("ids")
        ids
          .toTry { id =>
            observableSrv
              .update(_.get(EntityIdOrName(id)).can(Permissions.manageObservable), properties)
          }
          .map(_ => Results.NoContent)
      }

  def delete(observableId: String): Action[AnyContent] =
    entrypoint("delete")
      .authTransaction(db) { implicit request => implicit graph =>
        for {
          observable <-
            observableSrv
              .get(EntityIdOrName(observableId))
              .can(Permissions.manageObservable)
              .getOrFail("Observable")
          _ <- observableSrv.remove(observable)
        } yield Results.NoContent
      }

  // extract a file from the archive and make sure its size matches the header (to protect against zip bombs)
  private def extractAndCheckSize(zipFile: ZipFile, header: FileHeader): Option[FFile] = {
    val fileName = header.getFileName
    if (fileName.contains('/')) None
    else {
      val file = temporaryFileCreator.create("zip")

      val input = zipFile.getInputStream(header)
      val size  = header.getUncompressedSize
      val sizedInput: FilterInputStream = new FilterInputStream(input) {
        var totalRead = 0

        override def read(): Int =
          if (totalRead < size) {
            totalRead += 1
            super.read()
          } else throw BadRequestError("Error extracting file: output size doesn't match header")
      }
      Files.delete(file)
      val fileSize = Files.copy(sizedInput, file)
      if (fileSize != size) {
        file.toFile.delete()
        throw InternalError("Error extracting file: output size doesn't match header")
      }
      input.close()
      val contentType = Option(Files.probeContentType(file)).getOrElse("application/octet-stream")
      Some(FFile(header.getFileName, file, contentType))
    }
  }

  private def getZipFiles(observable: InputObservable, zipPassword: Option[String])(implicit authContext: AuthContext): Seq[InputObservable] =
    observable.attachment.toSeq.flatMap { attachment =>
      val zipFile                = new ZipFile(attachment.filepath.toFile)
      val files: Seq[FileHeader] = zipFile.getFileHeaders.asScala.asInstanceOf[Seq[FileHeader]]

      if (zipFile.isEncrypted)
        zipFile.setPassword(zipPassword.getOrElse(configuration.get[String]("datastore.attachment.password")).toCharArray)

      files
        .filterNot(_.isDirectory)
        .flatMap(extractAndCheckSize(zipFile, _))
        .map(ffile => observable.copy(attachment = Some(ffile)))
    }
}

@Singleton
class PublicObservable @Inject() (
    observableSrv: ObservableSrv,
    organisationSrv: OrganisationSrv
) extends PublicData
    with ObservableRenderer {
  override val entityName: String = "observable"
  override val initialQuery: Query =
    Query.init[Traversal.V[Observable]](
      "listObservable",
      (graph, authContext) => organisationSrv.get(authContext.organisation)(graph).shares.observables
    )
  override val getQuery: ParamQuery[EntityIdOrName] = Query.initWithParam[EntityIdOrName, Traversal.V[Observable]](
    "getObservable",
    FieldsParser[EntityIdOrName],
    (idOrName, graph, authContext) => observableSrv.get(idOrName)(graph).visible(authContext)
  )
  override val pageQuery: ParamQuery[OutputParam] =
    Query.withParam[OutputParam, Traversal.V[Observable], IteratorOutput](
      "page",
      FieldsParser[OutputParam],
      {
        case (OutputParam(from, to, withStats, 0), observableSteps, authContext) =>
          observableSteps
            .richPage(from, to, withTotal = true) {
              case o if withStats =>
                o.richObservableWithCustomRenderer(observableStatsRenderer(authContext))(authContext)
                  .domainMap(ros => (ros._1, ros._2, None: Option[RichCase]))
              case o =>
                o.richObservable.domainMap(ro => (ro, JsObject.empty, None))
            }
        case (OutputParam(from, to, _, _), observableSteps, authContext) =>
          observableSteps.richPage(from, to, withTotal = true)(
            _.richObservableWithCustomRenderer(o => o.`case`.richCase(authContext))(authContext).domainMap(roc =>
              (roc._1, JsObject.empty, Some(roc._2): Option[RichCase])
            )
          )
      }
    )
  override val outputQuery: Query = Query.output[RichObservable, Traversal.V[Observable]](_.richObservable)
  override val extraQueries: Seq[ParamQuery[_]] = Seq(
    //    Query.output[(RichObservable, JsObject, Option[RichCase])]
  )
  override val publicProperties: PublicProperties = PublicPropertyListBuilder[Observable]
    .property("status", UMapping.string)(_.select(_.constant("Ok")).readonly)
    .property("startDate", UMapping.date)(_.select(_._createdAt).readonly)
    .property("ioc", UMapping.boolean)(_.field.updatable)
    .property("sighted", UMapping.boolean)(_.field.updatable)
    .property("ignoreSimilarity", UMapping.boolean)(_.field.updatable)
    .property("tags", UMapping.string.set)(
      _.select(_.tags.displayName)
        .filter((_, cases) =>
          cases
            .tags
            .graphMap[String, String, Converter.Identity[String]](
              { v =>
                val namespace = UMapping.string.getProperty(v, "namespace")
                val predicate = UMapping.string.getProperty(v, "predicate")
                val value     = UMapping.string.optional.getProperty(v, "value")
                Tag(namespace, predicate, value, None, 0).toString
              },
              Converter.identity[String]
            )
        )
        .converter(_ => Converter.identity[String])
        .custom { (_, value, vertex, _, graph, authContext) =>
          observableSrv
            .get(vertex)(graph)
            .getOrFail("Observable")
            .flatMap(observable => observableSrv.updateTagNames(observable, value)(graph, authContext))
            .map(_ => Json.obj("tags" -> value))
        }
    )
    .property("message", UMapping.string)(_.field.updatable)
    .property("tlp", UMapping.int)(_.field.updatable)
    .property("dataType", UMapping.string)(_.select(_.observableType.value(_.name)).readonly)
    .property("data", UMapping.string.optional)(_.select(_.data.value(_.data)).readonly)
    .property("attachment.name", UMapping.string.optional)(_.select(_.attachments.value(_.name)).readonly)
    .property("attachment.size", UMapping.long.optional)(_.select(_.attachments.value(_.size)).readonly)
    .property("attachment.contentType", UMapping.string.optional)(_.select(_.attachments.value(_.contentType)).readonly)
    .property("attachment.hashes", UMapping.hash)(_.select(_.attachments.value(_.hashes)).readonly)
    .build
}
