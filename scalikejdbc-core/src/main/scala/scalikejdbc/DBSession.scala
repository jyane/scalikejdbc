package scalikejdbc

import java.sql._
import util.control.Exception._
import scala.collection.generic.CanBuildFrom
import scala.collection.breakOut
import scala.language.higherKinds

/**
 * DB Session
 *
 * This class provides readOnly/autoCommit/localTx/withinTx blocks and session objects.
 *
 * {{{
 * import scalikejdbc._
 *
 * val userIdList = DB autoCommit { session: DBSession =>
 *   session.list("select * from user") { rs => rs.int("id") }
 * }
 * }}}
 */
trait DBSession extends LogSupport with LoanPattern with AutoCloseable {

  protected[scalikejdbc] def settings: SettingsProvider

  protected def unexpectedInvocation[A]: A = {
    throw new IllegalStateException("This method should not be called.")
  }

  /**
   * Connection
   */
  lazy val connection: Connection = conn

  private[scalikejdbc] val conn: Connection

  private[scalikejdbc] val connectionAttributes: DBConnectionAttributes

  /**
   * Returns current transaction if exists.
   */
  def tx: Option[Tx] = None

  @volatile
  private[this] var _fetchSize: Option[Int] = None

  @volatile
  private[this] var _tags: Seq[String] = Vector.empty

  @volatile
  private[this] var _queryTimeout: Option[Int] = None

  /**
   * is read-only session
   */
  val isReadOnly: Boolean

  private[this] def isAutoGeneratedKeyRetrievalWithColumnName(driverName: String): Boolean = {
    settings.driverNamesToChooseColumnNameForAutoGeneratedKeyRetrieval(
      GlobalSettings.driverNamesToChooseColumnNameForAutoGeneratedKeyRetrieval
    ).contains(driverName)
  }

  /**
   * Create java.sql.Statement executor.
   *
   * @param conn connection
   * @param template SQL template
   * @param params parameters
   * @param returnGeneratedKeys is generated keys required
   * @param generatedKeyName generated key name
   * @return statement executor
   */
  private def createStatementExecutor(
    conn: Connection,
    template: String,
    params: Seq[Any],
    returnGeneratedKeys: Boolean = false,
    generatedKeyName: Option[String] = None
  ): StatementExecutor = {
    try {
      val statement = {
        if (returnGeneratedKeys) {
          (generatedKeyName, connectionAttributes.driverName) match {
            case (Some(key), Some(driverName)) if isAutoGeneratedKeyRetrievalWithColumnName(driverName) =>
              conn.prepareStatement(template, Seq(key).toArray)
            case _ =>
              conn.prepareStatement(template, Statement.RETURN_GENERATED_KEYS)
          }
        } else {
          conn.prepareStatement(template)
        }
      }
      this.fetchSize.foreach { size => statement.setFetchSize(size) }
      this.queryTimeout.foreach { seconds => statement.setQueryTimeout(seconds) }
      StatementExecutor(
        underlying = new DBConnectionAttributesWiredPreparedStatement(statement, connectionAttributes),
        template = template,
        connectionAttributes = connectionAttributes,
        tags = tags,
        singleParams = params,
        settingsProvider = settings
      )

    } catch {
      case e: Exception =>

        val formattedTemplate =
          settings.sqlFormatter(GlobalSettings.sqlFormatter).formatter match {
            case Some(formatter) =>
              try {
                formatter.format(template)
              } catch {
                case e: Exception =>
                  log.debug("Failed to format SQL because " + e.getMessage, e)
                  template
              }
            case None =>
          }

        if (settings.loggingSQLErrors(GlobalSettings.loggingSQLErrors)) {
          log.error("Failed preparing the statement (Reason: " + e.getMessage + "):\n\n  " + formattedTemplate + "\n")
        } else {
          log.debug("Logging SQL errors is disabled.")
        }

        settings.queryFailureListener(GlobalSettings.queryFailureListener).apply(template, params, e)
        settings.taggedQueryFailureListener(GlobalSettings.taggedQueryFailureListener).apply(template, params, e, tags)

        throw e
    }
  }

  def toStatementExecutor(template: String, params: Seq[Any],
    returnGeneratedKeys: Boolean = false): StatementExecutor = {
    createStatementExecutor(conn, template, params, returnGeneratedKeys)
  }

  /**
   * Create java.sql.Statement executor.
   * @param conn connection
   * @param template SQL template
   * @return statement executor
   */
  private def createBatchStatementExecutor(
    conn: Connection,
    template: String,
    returnGeneratedKeys: Boolean,
    generatedKeyName: Option[String]
  ): StatementExecutor = {
    val statement: PreparedStatement = {
      if (returnGeneratedKeys) {
        (generatedKeyName, connectionAttributes.driverName) match {
          case (Some(key), Some(driverName)) if isAutoGeneratedKeyRetrievalWithColumnName(driverName) =>
            conn.prepareStatement(template, Seq(key).toArray)
          case _ =>
            conn.prepareStatement(template, Statement.RETURN_GENERATED_KEYS)
        }
      } else {
        conn.prepareStatement(template)
      }
    }
    this.fetchSize.foreach { size => statement.setFetchSize(size) }
    this.queryTimeout.foreach { seconds => statement.setQueryTimeout(seconds) }
    StatementExecutor(
      underlying = new DBConnectionAttributesWiredPreparedStatement(statement, connectionAttributes),
      template = template,
      connectionAttributes = connectionAttributes,
      tags = tags,
      isBatch = true,
      settingsProvider = settings
    )
  }

  def toBatchStatementExecutor(template: String): StatementExecutor = {
    createBatchStatementExecutor(conn, template, false, None)
  }

  /**
   * Ensures the current session is not in read-only mode.
   * @param template
   */
  private def ensureNotReadOnlySession(template: String): Unit = {
    if (isReadOnly) {
      throw new java.sql.SQLException(
        ErrorMessage.CANNOT_EXECUTE_IN_READ_ONLY_SESSION + " (template:" + template + ")"
      )
    }
  }

  /**
   * Set fetchSize for this session.
   *
   * @param fetchSize fetch size
   * @return this
   */
  def fetchSize(fetchSize: Int): this.type = {
    this._fetchSize = Some(fetchSize)
    this
  }

  def fetchSize(fetchSize: Option[Int]): this.type = {
    this._fetchSize = fetchSize
    this
  }

  /**
   * Returns fetchSize for this session.
   *
   * @return fetch size
   */
  def fetchSize: Option[Int] = this._fetchSize

  /**
   * Set tags to this session.
   *
   * @param tags tags
   * @return this
   */
  def tags(tags: String*): this.type = {
    this._tags = tags
    this
  }

  /**
   * Returns tags for this session.
   *
   * @return tags
   */
  def tags: Seq[String] = this._tags

  /**
   * Set queryTimeout to this session.
   *
   * @param seconds query timeout seconds
   * @return this
   */
  def queryTimeout(seconds: Int): this.type = {
    this._queryTimeout = Some(seconds)
    this
  }

  def queryTimeout(seconds: Option[Int]): this.type = {
    this._queryTimeout = seconds
    this
  }

  /**
   * Returns queryTimeout for this session.
   *
   * @return query timeout seconds
   */
  def queryTimeout: Option[Int] = this._queryTimeout

  /**
   * Returns single result optionally.
   * If the result is not single, [[scalikejdbc.TooManyRowsException]] will be thrown.
   *
   * @param template SQL template
   * @param params parameters
   * @param extract extract function
   * @tparam A return type
   * @return result optionally
   */
  def single[A](template: String, params: Any*)(extract: WrappedResultSet => A): Option[A] = {
    using(createStatementExecutor(conn, template, params)) {
      executor =>
        val proxy = new DBConnectionAttributesWiredResultSet(executor.executeQuery(), connectionAttributes)
        val resultSet = new ResultSetTraversable(proxy)
        val rows = (resultSet map (rs => extract(rs))).toList
        rows match {
          case Nil => None
          case one :: Nil => Option(one)
          case _ => throw TooManyRowsException(1, rows.size)
        }
    }
  }

  /**
   * Returns the first row optionally.
   *
   * @param template SQL template
   * @param params parameters
   * @param extract extract function
   * @tparam A return type
   * @return result optionally
   */
  def first[A](template: String, params: Any*)(extract: WrappedResultSet => A): Option[A] = {
    traversable(template, params: _*)(extract).headOption
  }

  /**
   * Returns query result as scala.List object.
   *
   * @param template SQL template
   * @param params parameters
   * @param extract extract function
   * @tparam A return type
   * @return result as list
   */
  def list[A](template: String, params: Any*)(extract: WrappedResultSet => A): List[A] = {
    collection[A, List](template, params: _*)(extract)
  }

  /**
   * Returns query result as any Collection object.
   *
   * @param template SQL template
   * @param params parameters
   * @param extract extract function
   * @tparam A return type
   * @tparam C return collection type
   * @return result as C[A]
   */
  def collection[A, C[_]](template: String, params: Any*)(extract: WrappedResultSet => A)(implicit cbf: CanBuildFrom[Nothing, A, C[A]]): C[A] = {
    using(createStatementExecutor(conn, template, params)) {
      executor =>
        val proxy = new DBConnectionAttributesWiredResultSet(executor.executeQuery(), connectionAttributes)
        new ResultSetTraversable(proxy).map(extract)(breakOut)
    }
  }

  /**
   * Applies side-effect to each row iteratively.
   *
   * @param template SQL template
   * @param params parameters
   * @param f function
   * @return result as list
   */
  def foreach(template: String, params: Any*)(f: WrappedResultSet => Unit): Unit = {
    using(createStatementExecutor(conn, template, params)) {
      executor =>
        val proxy = new DBConnectionAttributesWiredResultSet(executor.executeQuery(), connectionAttributes)
        new ResultSetTraversable(proxy) foreach (rs => f(rs))
    }
  }

  /**
   * folding into one value.
   *
   * @param template SQL template
   * @param params parameters
   * @param z initial value
   * @param op function
   * @return folded value
   */
  def foldLeft[A](template: String, params: Any*)(z: A)(op: (A, WrappedResultSet) => A): A = {
    using(createStatementExecutor(conn, template, params)) {
      executor =>
        val proxy = new DBConnectionAttributesWiredResultSet(executor.executeQuery(), connectionAttributes)
        new ResultSetTraversable(proxy).foldLeft(z)(op)
    }
  }

  /**
   * Returns query result as scala.collection.Traversable object.
   *
   * @param template SQL template
   * @param params parameters
   * @param extract extract function
   * @tparam A return type
   * @return result as traversable
   */
  def traversable[A](template: String, params: Any*)(extract: WrappedResultSet => A): Traversable[A] = {
    collection[A, Traversable](template, params: _*)(extract)
  }

  /**
   * Executes java.sql.PreparedStatement#execute().
   *
   * @param template SQL template
   * @param params  parameters
   * @return flag
   */
  def execute(template: String, params: Any*): Boolean = {
    ensureNotReadOnlySession(template)
    using(createStatementExecutor(conn, template, params)) {
      executor => executor.execute()
    }
  }

  /**
   * Executes java.sql.PreparedStatement#execute().
   *
   * @param before before filter
   * @param after after filter
   * @param template SQL template
   * @param params parameters
   * @return flag
   */
  def executeWithFilters(
    before: (PreparedStatement) => Unit,
    after: (PreparedStatement) => Unit,
    template: String,
    params: Any*
  ): Boolean = {
    ensureNotReadOnlySession(template)
    using(createStatementExecutor(conn, template, params)) {
      executor =>
        before(executor.underlying)
        val result = executor.execute()
        after(executor.underlying)
        result
    }
  }

  /**
   * Executes java.sql.PreparedStatement#executeUpdate().
   *
   * @param template SQL template
   * @param params  parameters
   * @return result count
   */
  def executeUpdate(template: String, params: Any*): Int = update(template, params: _*)

  /**
   * Executes java.sql.PreparedStatement#executeUpdate().
   *
   * @param template SQL template
   * @param params parameters
   * @return result count
   */
  def update(template: String, params: Any*): Int = {
    ensureNotReadOnlySession(template)
    using(createStatementExecutor(conn, template, params)) {
      executor => executor.executeUpdate()
    }
  }

  /**
   * Executes java.sql.PreparedStatement#executeUpdate().
   *
   * @param before before filter
   * @param after after filter
   * @param template SQL template
   * @param params parameters
   * @return  result count
   */
  def updateWithFilters(
    before: (PreparedStatement) => Unit,
    after: (PreparedStatement) => Unit,
    template: String,
    params: Any*
  ): Int = updateWithFilters(false, before, after, template, params: _*)

  /**
   * Executes java.sql.PreparedStatement#executeUpdate().
   *
   * @param returnGeneratedKeys is generated keys required
   * @param before before filter
   * @param after after filter
   * @param template SQL template
   * @param params parameters
   * @return  result count
   */
  def updateWithFilters(
    returnGeneratedKeys: Boolean,
    before: (PreparedStatement) => Unit,
    after: (PreparedStatement) => Unit,
    template: String,
    params: Any*
  ): Int = {
    ensureNotReadOnlySession(template)
    using(createStatementExecutor(
      conn = conn,
      template = template,
      params = params,
      returnGeneratedKeys = returnGeneratedKeys
    )) {
      executor =>
        before(executor.underlying)
        val count = executor.executeUpdate()
        after(executor.underlying)
        count
    }
  }

  /**
   * Executes java.sql.PreparedStatement#executeUpdate().
   *
   * @param returnGeneratedKeys is generated keys required
   * @param generatedKeyName generated key name
   * @param before before filter
   * @param after after filter
   * @param template SQL template
   * @param params parameters
   * @return  result count
   */
  def updateWithAutoGeneratedKeyNameAndFilters(
    returnGeneratedKeys: Boolean,
    generatedKeyName: String,
    before: (PreparedStatement) => Unit,
    after: (PreparedStatement) => Unit,
    template: String,
    params: Any*
  ): Int = {
    ensureNotReadOnlySession(template)
    using(createStatementExecutor(
      conn = conn,
      template = template,
      params = params,
      returnGeneratedKeys = returnGeneratedKeys,
      generatedKeyName = Option(generatedKeyName)
    )) {
      executor =>
        before(executor.underlying)
        val count = executor.executeUpdate()
        after(executor.underlying)
        count
    }
  }

  /**
   * Executes java.sql.PreparedStatement#executeUpdate() and returns the generated key.
   *
   * @param template SQL template
   * @param params parameters
   * @return generated key as a long value
   */
  def updateAndReturnGeneratedKey(template: String, params: Any*): Long = updateAndReturnSpecifiedGeneratedKey(template, params: _*)(1)

  /**
   * Executes java.sql.PreparedStatement#executeUpdate() and returns the generated key.
   *
   * @param template SQL template
   * @param params parameters
   * @param key name
   * @return generated key as a long value
   */
  def updateAndReturnSpecifiedGeneratedKey(template: String, params: Any*)(key: Any): Long = {
    var generatedKeyFound = false
    var generatedKey: Long = -1
    val before = (stmt: PreparedStatement) => {}
    val after = (stmt: PreparedStatement) => {
      val rs = stmt.getGeneratedKeys
      while (rs.next()) {
        generatedKeyFound = true
        generatedKey = key match {
          case name: String => try {
            rs.getLong(name)
          } catch {
            case e: Exception =>
              log.warn("Failed to get generated key value via index " + name + ". Going to retrieve it via index 1.")
              rs.getLong(1)
          }
          case index: Int => try {
            rs.getLong(index)
          } catch {
            case e: Exception =>
              log.warn("Failed to get generated key value via index " + index + ". Going to retrieve it via index 1.")
              rs.getLong(1)
          }
          case _ => throw new IllegalArgumentException(ErrorMessage.FAILED_TO_RETRIEVE_GENERATED_KEY + "(key:" + key + ")")
        }
      }
    }
    key match {
      case k: String => updateWithAutoGeneratedKeyNameAndFilters(true, k, before, after, template, params: _*)
      case _ => updateWithFilters(true, before, after, template, params: _*)
    }
    if (!generatedKeyFound) {
      throw new IllegalStateException(ErrorMessage.FAILED_TO_RETRIEVE_GENERATED_KEY + " (template:" + template + ")")
    }
    generatedKey
  }

  /**
   * Executes java.sql.PreparedStatement#executeBatch().
   * @param template SQL template
   * @param paramsList list of parameters
   * @return count list
   */
  def batch[C[_]](template: String, paramsList: Seq[Any]*)(implicit cbf: CanBuildFrom[Nothing, Int, C[Int]]): C[Int] = {
    ensureNotReadOnlySession(template)
    paramsList match {
      case Nil => Seq.empty[Int].to[C]
      case _ =>
        using(createBatchStatementExecutor(
          conn = conn,
          template = template,
          returnGeneratedKeys = false,
          generatedKeyName = None
        )) { executor =>
          paramsList.foreach {
            params =>
              executor.bindParams(params)
              executor.addBatch()
          }
          executor.executeBatch().to[C]
        }
    }
  }

  /**
   * Executes java.sql.PreparedStatement#executeBatch() and returns numeric generated keys.
   *
   * @param template SQL template
   * @param paramsList list of parameters
   * @return generated keys
   */
  def batchAndReturnGeneratedKey[C[_]](template: String, paramsList: Seq[Any]*)(
    implicit
    cbf: CanBuildFrom[Nothing, Long, C[Long]]
  ): C[Long] = {
    ensureNotReadOnlySession(template)
    paramsList match {
      case Nil => Seq.empty[Long].to[C]
      case _ =>
        using(createBatchStatementExecutor(
          conn = conn,
          template = template,
          returnGeneratedKeys = true,
          generatedKeyName = None
        )) { executor =>
          paramsList.foreach {
            params =>
              executor.bindParams(params)
              executor.addBatch()
          }
          executor.executeBatch()
          new ResultSetTraversable(executor.generatedKeysResultSet).map(_.long(1)).to[C]
        }
    }
  }

  /**
   * Executes java.sql.PreparedStatement#executeBatch() and returns numeric generated keys.
   *
   * @param template SQL template
   * @param key generated key name
   * @param paramsList list of parameters
   * @return generated keys
   */
  def batchAndReturnSpecifiedGeneratedKey[C[_]](template: String, key: String, paramsList: Seq[Any]*)(
    implicit
    cbf: CanBuildFrom[Nothing, Long, C[Long]]
  ): C[Long] = {
    ensureNotReadOnlySession(template)
    paramsList match {
      case Nil => Seq.empty[Long].to[C]
      case _ =>
        using(createBatchStatementExecutor(
          conn = conn,
          template = template,
          returnGeneratedKeys = true,
          generatedKeyName = Some(key)
        )) { executor =>
          paramsList.foreach {
            params =>
              executor.bindParams(params)
              executor.addBatch()
          }
          executor.executeBatch()
          new ResultSetTraversable(executor.generatedKeysResultSet).map(_.long(key)).to[C]
        }
    }
  }

  /**
   * Close the connection.
   */
  def close(): Unit = {
    ignoring(classOf[Throwable]) {
      conn.close()
    }
    if (settings.loggingConnections(GlobalSettings.loggingConnections)) {
      log.debug("A Connection is closed.")
    }
  }

}

object DBSession {

  def apply(
    conn: Connection,
    tx: Option[Tx] = None,
    isReadOnly: Boolean = false,
    connectionAttributes: DBConnectionAttributes = DBConnectionAttributes(),
    settings: SettingsProvider = SettingsProvider.default
  ): DBSession = {
    ActiveSession(conn, connectionAttributes, tx, isReadOnly, settings)
  }

}

/**
 * Active session implementation of [[scalikejdbc.DBSession]].
 *
 * This class provides readOnly/autoCommit/localTx/withinTx blocks and session objects.
 *
 * {{{
 * import scalikejdbc._
 *
 * val userIdList = DB autoCommit { session: DBSession =>
 *   session.list("select * from user") { rs => rs.int("id") }
 * }
 * }}}
 *
 * @param conn connection
 * @param tx transaction
 * @param isReadOnly is read only
 */
case class ActiveSession(
    private[scalikejdbc] val conn: Connection,
    private[scalikejdbc] val connectionAttributes: DBConnectionAttributes,
    override val tx: Option[Tx] = None,
    isReadOnly: Boolean = false,
    settings: SettingsProvider = SettingsProvider.default
) extends DBSession {

  tx match {
    case Some(tx) if tx.isActive() => // nothing to do
    case None =>
      if (!settings.jtaDataSourceCompatible(GlobalSettings.jtaDataSourceCompatible)) {
        conn.setAutoCommit(true)
      }
    case _ => throw new IllegalStateException(ErrorMessage.TRANSACTION_IS_NOT_ACTIVE)
  }
}

/**
 * Represents that there is no active session.
 */
case object NoSession extends DBSession {
  override private[scalikejdbc] val conn: Connection = null
  override val tx: Option[Tx] = None
  val isReadOnly: Boolean = false

  override def fetchSize(fetchSize: Int): this.type = unexpectedInvocation
  override def fetchSize(fetchSize: Option[Int]): this.type = unexpectedInvocation
  override def tags(tags: String*): this.type = unexpectedInvocation
  override def queryTimeout(seconds: Int): this.type = unexpectedInvocation
  override def queryTimeout(seconds: Option[Int]): this.type = unexpectedInvocation
  override private[scalikejdbc] lazy val connectionAttributes: DBConnectionAttributes = unexpectedInvocation
  override protected[scalikejdbc] def settings = SettingsProvider.default
}

