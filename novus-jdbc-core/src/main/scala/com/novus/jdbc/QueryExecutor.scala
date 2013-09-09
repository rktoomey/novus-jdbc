package com.novus.jdbc

import java.sql.{ Connection, Statement }
import org.slf4j.LoggerFactory

/**
 * The container object for an underlying database connection pool. All queries are both timed and logged to an
 * [[org.slf4j.Logger]] which must be configured to the actual logging framework. It is assumed, although not enforced
 * at compile time, that all statements are DML queries. Implementations of `QueryExecutor` must define the #connection
 * and the #shutdown methods.
 *
 * @since 0.1
 * @tparam DBType The database type
 *
 * @note Warning: Does not work properly with parameter objects that can only be traversed once.
 */
trait QueryExecutor[DBType] {
  val log = LoggerFactory getLogger this.getClass

  /** Obtain a connection from the underlying connection pool */
  protected def connection(): Connection

  /**
   * Responsible for obtaining and returning a DB connection from the connection pool to execute the given query
   * function.
   *
   * @param q The query statement
   * @param params The query parameters
   * @param f Any one of the select, update, delete or merge commands
   * @tparam T The return type of the query
   */
  @inline final protected def execute[T](q: String, params: Any*)(f: Connection => T): T ={
    val msg = """
      QUERY:  %s
      PARAMS: %s
    """ format (q, params mkString (", "))

    executeQuery(msg, f)
  }

  /**
   * Responsible for obtaining and returning a DB connection from the connection pool to execute the given query
   * function.
   *
   * @param q The query statement
   * @param f Any one of the select, update, delete or merge commands
   * @tparam T The return type of the query
   */
  @inline final protected def execute[T](q: String)(f: Connection => T): T ={
    val msg = """
      QUERY:  %s
    """ format q

    executeQuery(msg, f)
  }

  /**
   * Allow caller to execute statements.
   * @param q The query statement to execute
   * @return <code>true</code> if the first result is a <code>ResultSet</code>
   *         object; <code>false</code> if it is an update count or there are
   *         no results
   */
  @inline final def executeStatement(q: String): Boolean = {
    val now = System.currentTimeMillis
    val con = connection()
    try {
      val output = {
        val stmt = con.createStatement()
        stmt.execute(q)
      }
      val later = System.currentTimeMillis

      log info("Timed: {} timed for {} ms", """
                                              |           QUERY: %s
                                              |          RESULT: %s
                                            """.stripMargin.format(q, output), later - now)

      output
    }
    catch {
      case ex: NullPointerException => log error("{} pool object returned a null connection", this); throw ex
      case ex: Exception => log error("%s, threw exception" format this, ex); throw ex
    }
    finally {
      if (con != null) con close()
    }
  }

  /**
   * Allow caller to execute update statements.
   * @param q The query statement to execute
   * @return either (1) the row count for SQL Data Manipulation Language (DML) statements
   *         or (2) 0 for SQL statements that return nothing
   */
  @inline final def executeUpdate(q: String): Int = {
    val now = System.currentTimeMillis
    val con = connection()
    try {
      val output = {
        val stmt = con.createStatement()
        stmt.executeUpdate(q)
      }
      val later = System.currentTimeMillis

      log info("Timed: {} timed for {} ms", """
                                              |           QUERY: %s
                                              |          RESULT: %s
                                            """.stripMargin.format(q, output), later - now)

      output
    }
    catch {
      case ex: NullPointerException => log error("{} pool object returned a null connection", this); throw ex
      case ex: Exception => log error("%s, threw exception" format this, ex); throw ex
    }
    finally {
      if (con != null) con close()
    }
  }

  /**
   * Responsible for obtaining and returning a DB connection from the connection pool to execute the given query
   * function.
   *
   * @param msg The timing message
   * @param f Any one of the select, update, delete or merge commands
   * @tparam T The return type of the query
   */
  final private def executeQuery[T](msg: String, f: Connection => T): T = {
    val now = System.currentTimeMillis
    val con = connection()
    try {
      val output = f(con)
      val later = System.currentTimeMillis

      log info ("Timed: {} timed for {} ms", msg, later - now)

      output
    }
    catch {
      case ex: NullPointerException => log error ("{} pool object returned a null connection", this); throw ex
      case ex: Exception            => log error ("{}, threw exception" format this, ex); throw ex
    }
    finally {
      if (con != null) con close ()
    }
  }

  /** Returns an iterator containing update counts. */
  final def executeBatch[I <: Seq[Any]](batchSize: Int = 1000)(q: String, params: Iterator[I])(implicit query: Queryable[DBType]): Iterator[Int] =
    execute(q, params) { query.executeBatch(batchSize)(q, params) }

  /**
   * Execute a query and transform only the head of the `RichResultSet`. If this query would produce multiple results,
   * they are lost.
   *
   * @param q The query statement
   * @param params The query parameters
   * @param f A transform from a `RichResultSet` to a type `T`
   * @tparam T The returned type from the query
   */
  final def selectOne[T](q: String, params: Any*)(f: RichResultSet => T)(implicit query: Queryable[DBType]): Option[T] = {
    val (stmt, rs) = execute(q, params: _*) { query select (q, params: _*) }

    one(stmt, rs, f)
  }

  /**
   * Execute a query and transform only the head of the `RichResultSet`. If this query would produce multiple results,
   * they are lost.
   *
   * @param q The query statement
   * @param f A transform from a `RichResultSet` to a type `T`
   * @tparam T The returned type from the query
   */
  final def selectOne[T](q: String)(f: RichResultSet => T)(implicit query: Queryable[DBType]): Option[T] ={
    val (stmt, rs) = execute(q){ query select q }

    one(stmt, rs, f)
  }

  /**
   * Given the products of an executed query, transforms only the first result using the supplied function `f`.
   *
   * @param stmt A [[java.sql.Statement]]
   * @param rs A `RichResultSet`
   * @param f A transformation from a `RichResultSet` to a type `T`
   * @tparam T The return type from the query
   */
  final private def one[T](stmt: Statement, rs: RichResultSet, f: RichResultSet => T): Option[T] = try{
    if (rs next ()) Some(f(rs)) else None
  }
  finally{
    stmt close ()
  }

  /**
   * Execute a query and yield a [[com.novus.jdbc.CloseableIterator]] which, as consumed, will progress through the
   * underlying `RichResultSet` and lazily evaluate the argument function.
   *
   * @param q The query statement
   * @param params The query parameters
   * @param f A transform from a `RichResultSet` to a type `T`
   * @tparam T The returned type from the query
   */
  final def select[T](q: String, params: Any*)(f: RichResultSet => T)(implicit query: Queryable[DBType]): CloseableIterator[T] = {
    val (stmt, rs) = execute(q, params: _*) { query select (q, params: _*) }

    new ResultSetIterator(stmt, rs, f)
  }

  /**
   * Execute a query and yield a [[com.novus.jdbc.CloseableIterator]] which, as consumed, will progress through the
   * underlying `RichResultSet` and lazily evaluate the argument function.
   *
   * @param q The query statement
   * @param f A transform from a `RichResultSet` to a type `T`
   * @tparam T The returned type from the query
   */
  final def select[T](q: String)(f: RichResultSet => T)(implicit query: Queryable[DBType]): CloseableIterator[T] = {
    val (stmt, rs) = execute(q) { query select q }

    new ResultSetIterator(stmt, rs, f)
  }

  /**
   * Eagerly evaluates the argument function against the returned `RichResultSet`.
   *
   * @see #select
   */
  final def eagerlySelect[T](q: String, params: Any*)(f: RichResultSet => T)(implicit query: Queryable[DBType]): List[T] =
    select(q, params: _*)(f)(query).toList

  /**
   * Eagerly evaluates the argument function against the returned `RichResultSet`.
   *
   * @see #select
   */
  final def eagerlySelect[T](q: String)(f: RichResultSet => T)(implicit query: Queryable[DBType]): List[T] =
    select(q)(f)(query).toList

  /**
   * Returns an iterator containing the ID column of the rows which were inserted by this insert statement.
   *
   * @param q The query statement
   * @param params The query parameters
   */
  final def insert(q: String, params: Any*)(implicit query: Queryable[DBType]): CloseableIterator[Int] =
    execute(q, params: _*) { query insert (q, params: _*) }

  /**
   * Returns an iterator containing the compound index column of the rows which were inserted by this insert statement.
   *
   * @param columns The index of each column which represents the auto generated key
   * @param q The query statement
   * @param params The query parameters
   * @param f A transform from a `RichResultSet` to a type `T`
   * @tparam T The return type of the query
   */
  final def insert[T](columns: Array[Int], q: String, params: Any*)(f: RichResultSet => T)(implicit query: Queryable[DBType]): CloseableIterator[T] = {
    val (stmt, rs) = execute(q, params: _*){ query insert (columns, q, params: _*) }

    new ResultSetIterator(stmt, rs, f)
  }

  /**
   * Returns an iterator containing the compound index column of the rows which were inserted by this insert statement.
   *
   * @param columns The index of each column which represents the auto generated key
   * @param q The query statement
   * @param params The query parameters
   * @param f A transform from a `RichResultSet` to a type `T`
   * @tparam T The return type of the query
   */
  final def insert[T](columns: Array[String], q: String, params: Any*)(f: RichResultSet => T)(implicit query: Queryable[DBType]): CloseableIterator[T] = {
    val (stmt, rs) = execute(q, params: _*){ query insert (columns, q, params: _*) }

    new ResultSetIterator(stmt, rs, f)
  }

  /**
   * Returns an iterator containing the ID column of the rows which were inserted by this insert statement.
   *
   * @param q The query statement
   */
  final def insert(q: String)(implicit query: Queryable[DBType]): CloseableIterator[Int] = execute(q) { query insert q }

  /**
   * Returns an iterator containing the compound index column of the rows which were inserted by this insert statement.
   *
   * @param columns The index of each column which represents the auto generated key
   * @param q The query statement
   * @param f A transform from a `RichResultSet` to a type `T`
   * @tparam T The return type of the query
   */
  final def insert[T](columns: Array[Int], q: String)(f: RichResultSet => T)(implicit query: Queryable[DBType]): CloseableIterator[T] = {
    val (stmt, rs) = execute(q){ query insert (columns, q) }

    new ResultSetIterator(stmt, rs, f)
  }

  /**
   * Returns an iterator containing the compound index column of the rows which were inserted by this insert statement.
   *
   * @param columns The index of each column which represents the auto generated key
   * @param q The query statement
   * @param f A transform from a `RichResultSet` to a type `T`
   * @tparam T The return type of the query
   */
  final def insert[T](columns: Array[String], q: String)(f: RichResultSet => T)(implicit query: Queryable[DBType]): CloseableIterator[T] = {
    val (stmt, rs) = execute(q){ query insert (columns, q) }

    new ResultSetIterator(stmt, rs, f)
  }

  /**
   * Returns the row count updated by this SQL statement. If the SQL statement is not a row update operation, such as a
   * DDL statement, then a 0 is returned.
   *
   * @param q The query statement
   * @param params The query parameters
   */
  final def update(q: String, params: Any*)(implicit query: Queryable[DBType]): Int =
    execute(q, params: _*) { query update (q, params: _*) }

  /**
   * Returns the row count updated by this SQL statement. If the SQL statement is not a row update operation, such as a
   * DDL statement, then a 0 is returned.
   *
   * @param q The query statement
   */
  final def update(q: String)(implicit query: Queryable[DBType]): Int = execute(q) { query update q }

  /**
   * Returns the row count deleted by this SQL statement.
   *
   * @param q The query statement
   * @param params The query parameters
   */
  final def delete(q: String, params: Any*)(implicit query: Queryable[DBType]): Int =
    execute(q, params: _*) { query delete (q, params: _*) }

  /**
   * Returns the row count deleted by this SQL statement.
   *
   * @param q The query statement
   */
  final def delete(q: String)(implicit query: Queryable[DBType]): Int = execute(q) { query delete q }

  /**
   * Returns an iterator containing the ID column which was inserted as a result of the merge statement. If this merge
   * statement does not cause an insertion into a table generating new IDs, the iterator returns empty. It is suggested
   * that update be used in the case where row counts affected is preferable to IDs.
   *
   * @param q The query statement
   * @param params The query parameters
   */
  final def merge(q: String, params: Any*)(implicit query: Queryable[DBType]): CloseableIterator[Int] =
    execute(q, params: _*) { query merge (q, params: _*) }

  /**
   * Returns an iterator containing the ID column which was inserted as a result of the merge statement. If this merge
   * statement does not cause an insertion into a table generating new IDs, the iterator returns empty. It is suggested
   * that update be used in the case where row counts affected is preferable to IDs.
   *
   * @param q The query statement
   */
  final def merge(q: String)(implicit query: Queryable[DBType]): CloseableIterator[Int] = execute(q) { query merge q }

  /** Shuts down the underlying connection pool. Should be called before this object is garbage collected. */
  def shutdown()
}