package com.github.ldaniels528.qwery

import java.io.FileInputStream
import java.util.Properties

import com.github.ldaniels528.qwery.ops.{Describe, Executable, Expression, Field, Hints, Insert, InsertValues, OrderedColumn, Select}
import com.github.ldaniels528.qwery.sources.DataResource
import com.github.ldaniels528.qwery.util.OptionHelper._
import com.github.ldaniels528.qwery.util.PeekableIterator
import com.github.ldaniels528.qwery.util.StringHelper._

import scala.util.{Failure, Success, Try}

/**
  * SQL Template Parser
  * @author lawrence.daniels@gmail.com
  */
class SQLTemplateParser(stream: TokenStream) extends ExpressionParser {

  /**
    * Indicates whether the given stream matches the given template
    * @param template the given template
    * @return true, if the stream matches the template from its current position
    */
  def matches(template: String): Boolean = {
    stream.mark()
    try new SQLTemplateParser(stream).process(template).nonEmpty catch {
      case _: Throwable => false
    } finally stream.reset()
  }

  /**
    * Extracts the tokens that correspond to the given template
    * @param template the given template (e.g. "INSERT INTO @table ( @(fields) ) VALUES ( @[values] )")
    * @return a mapping of the extracted values
    */
  def process(template: String): SQLTemplateParams = {
    var results = SQLTemplateParams()
    val tags = new PeekableIterator(template.split("[ ]").map(_.trim))
    while (tags.hasNext) {
      processNextTag(tags.next(), tags) foreach (params => results = results + params)
    }
    results
  }

  /**
    * Extracts and evaluates the next tag in the sequence
    * @param aTag the given tag (e.g. "@fields")
    * @param tags the [[PeekableIterator iteration]] of tags
    * @return the option of the resultant [[SQLTemplateParams template parameters]]
    */
  private def processNextTag(aTag: String, tags: PeekableIterator[String]): Option[SQLTemplateParams] = aTag match {
    // repeat start/end tag? (e.g. "{{values VALUES ( @{values} ) }}" => "VALUES (123, 456) VALUES (345, 678)")
    case tag if tag.startsWith("{{") => Some(processRepeatedSequence(name = tag.drop(2), tags))

    // conditional expression? (e.g. "@!{condition}" => "x = 1 and y = 2")
    case tag if tag.startsWith("@!{") & tag.endsWith("}") => Some(extractCondition(tag.drop(3).dropRight(1)))

    // query expression?
    case tag if tag.startsWith("@&") => Some(extractQueryExpression(tag.drop(2)))

    // field names? (e.g. "@(fields)" => "field1, field2, ..., fieldN")
    case tag if tag.startsWith("@(") & tag.endsWith(")") => Some(extractListOfFields(tag.drop(2).dropRight(1)))

    // expressions? (e.g. "@{fields}" => "field1, 'hello', 5 + now(), ..., fieldN")
    case tag if tag.startsWith("@{") & tag.endsWith("}") => Some(extractListOfExpressions(tag.drop(2).dropRight(1)))

    // ordered field list? (e.g. "@[orderedFields]" => "field1 DESC, field2 ASC")
    case tag if tag.startsWith("@[") & tag.endsWith("]") => Some(extractOrderedColumns(tag.drop(2).dropRight(1)))

    // enumeration? (e.g. "@|mode|INTO|OVERWRITE|" => "INSERT INTO ..." || "INSERT OVERWRITE ...")
    case tag if tag.startsWith("@|") & tag.endsWith("|") => Some(extractEnumeratedItem(tag.drop(2).dropRight(1).split('|')))

    // regular expression match? (e.g. "@/\\d{3,4}S+/" => "123ABC")
    case tag if tag.startsWith("@/") & tag.endsWith("/") =>
      val pattern = tag.drop(2).dropRight(1)
      if (stream.matches(pattern)) None else stream.die(s"Did not match the expected pattern '$pattern'")

    // atom? (e.g. "@table" => "'./tickers.csv'")
    case tag if tag.startsWith("@") => Some(extractIdentifier(tag.drop(1)))

    // optionally required tag? (e.g. "?TOP ?@top" => "TOP 100")
    case tag if tag.startsWith("?@") | tag.startsWith("?{{") => processNextTag(tag.drop(1), tags)

    // optionally required atom? (e.g. "?ORDER +?BY ?@|sortFields|" => "ORDER BY Symbol DESC")
    case tag if tag.startsWith("+?") => stream.expect(tag.drop(2)); None

    // optional atom? (e.g. "?LIMIT ?@limit" => "LIMIT 100")
    case tag if tag.startsWith("?") => extractOptional(tag.drop(1), tags); None

    // must be literal text
    case text => stream.expect(text); None
  }

  /**
    * Extracts an expression from the token stream
    * @param name the given identifier name (e.g. "condition")
    * @return a [[SQLTemplateParams template]] represents the parsed outcome
    */
  private def extractCondition(name: String) = {
    val condition = parseCondition(stream)
    SQLTemplateParams(conditions = Map(name -> condition.getOrElse(stream.die("Conditional expression expected"))))
  }

  /**
    * Extracts an enumerated item
    * @param values the values
    * @return a [[SQLTemplateParams template]] represents the parsed outcome
    */
  private def extractEnumeratedItem(values: Seq[String]) = {
    def error[A](items: List[String]) = stream.die[A](s"One of the following '${items.mkString(", ")}' identifiers is expected")

    values.toList match {
      case name :: items =>
        val item = stream.nextOption.map(_.text).getOrElse(error(items))
        if (!items.contains(item)) error(items)
        SQLTemplateParams(atoms = Map(name -> item))
      case _ =>
        stream.die(s"Unexpected template error: ${values.mkString(", ")}")
    }
  }

  /**
    * Extracts an identifier from the token stream
    * @param name the given identifier name (e.g. "source")
    * @return a [[SQLTemplateParams template]] represents the parsed outcome
    */
  private def extractIdentifier(name: String) = {
    val value = stream.nextOption.map(_.text).getOrElse(stream.die(s"'$name' identifier expected"))
    SQLTemplateParams(atoms = Map(name -> value))
  }

  /**
    * Extracts a field list by name from the token stream
    * @param name the given identifier name (e.g. "fields")
    * @return a [[SQLTemplateParams template]] represents the parsed outcome
    */
  private def extractListOfFields(name: String) = {
    var fields: List[Field] = Nil
    do {
      fields = fields ::: stream.nextOption.map(t => Field(t.text)).getOrElse(stream.dieEOS) :: Nil
    } while (stream nextIf ",")
    SQLTemplateParams(fields = Map(name -> fields))
  }

  /**
    * Extracts a field argument list from the token stream
    * @param name the given identifier name (e.g. "customerId, COUNT(*)")
    * @return a [[SQLTemplateParams template]] represents the parsed outcome
    */
  private def extractListOfExpressions(name: String) = {

    def fetchNext(ts: TokenStream): Expression = {
      val expression = parseExpression(ts)
      val result = if (ts nextIf "AS") expression.map(parseNamedAlias(ts, _)) else expression
      result.getOrElse(ts.dieEOS)
    }

    var expressions: List[Expression] = Nil
    do expressions = expressions ::: fetchNext(stream) :: Nil while (stream.nextIf(","))
    SQLTemplateParams(expressions = Map(name -> expressions))
  }

  /**
    * Extracts an optional view
    * @param name the named identifier
    * @param tags the [[PeekableIterator iterator]]
    */
  private def extractOptional(name: String, tags: PeekableIterator[String]): Unit = {
    if (!stream.nextIf(name)) {
      // if the option tag wasn't matched, skip any associated arguments
      while (tags.hasNext && tags.peek.exists(tag => tag.startsWith("?@") || tag.startsWith("+?"))) tags.next()
    }
  }

  private def extractQueryExpression(name: String) = {
    val executable = stream match {
      case ts if ts nextIf "(" =>
        val result = SQLTemplateParser.parseNext(stream)
        stream expect ")"
        result
      case ts if ts.isQuoted => DataResource(ts.next().text)
      case ts => ts.die("Query expression expected")
    }
    SQLTemplateParams(sources = Map(name -> executable))
  }

  /**
    * Extracts a repeated sequence from the stream
    * @param name the named identifier
    * @param tags the [[PeekableIterator iterator]]
    * @return the [[SQLTemplateParams SQL template parameters]]
    */
  private def processRepeatedSequence(name: String, tags: PeekableIterator[String]) = {
    // extract the repeated sequence
    val repeatedTagsSeq = extractRepeatedSequence(tags)
    var paramSet: List[SQLTemplateParams] = Nil
    var done = false
    while (!done && stream.hasNext) {
      var result: Option[SQLTemplateParams] = None
      val count = paramSet.size
      val repeatedTags = new PeekableIterator(repeatedTagsSeq)
      while (repeatedTags.hasNext) {
        result = processNextTag(repeatedTags.next(), repeatedTags)
        result.foreach(params => paramSet = paramSet ::: params :: Nil)
      }

      // if we didn't add anything, stop.
      done = paramSet.size == count
    }
    SQLTemplateParams(repeatedSets = Map(name -> paramSet))
  }

  private def extractRepeatedSequence(tags: Iterator[String]) = {
    tags.takeWhile(_ != "}}").toSeq
  }

  /**
    * Extracts a list of sort columns from the token stream
    * @param name the given identifier name (e.g. "sortedColumns")
    * @return a [[SQLTemplateParams template]] represents the parsed outcome
    */
  private def extractOrderedColumns(name: String) = {
    var sortFields: List[OrderedColumn] = Nil
    do {
      val name = stream.nextOption.map(_.text).getOrElse(stream.dieEOS)
      val direction = stream match {
        case ts if ts.nextIf("ASC") => true
        case ts if ts.nextIf("DESC") => false
        case _ => true
      }
      sortFields = sortFields ::: OrderedColumn(name, direction) :: Nil
    } while (stream.nextIf(","))
    SQLTemplateParams(orderedFields = Map(name -> sortFields))
  }

}

/**
  * Template Parser Companion
  * @author lawrence.daniels@gmail.com
  */
object SQLTemplateParser {

  /**
    * Creates a new TokenStream instance
    * @param query the given query string
    * @return the [[SQLTemplateParser template parser]]
    */
  def apply(query: String): SQLTemplateParser = new SQLTemplateParser(TokenStream(query))

  /**
    * Creates a new TokenStream instance
    * @param ts the given [[TokenStream token stream]]
    * @return the [[SQLTemplateParser template parser]]
    */
  def apply(ts: TokenStream): SQLTemplateParser = new SQLTemplateParser(ts)

  /**
    * Parses the next query or statement from the stream
    * @param stream the given [[TokenStream token stream]]
    * @return an [[Executable]]
    */
  def parseNext(stream: TokenStream): Executable = {
    stream match {
      case ts if ts is "DESCRIBE" => parseDescribe(ts)
      case ts if ts is "INSERT" => parseInsert(ts)
      case ts if ts is "SELECT" => parseSelect(ts)
      case ts => ts.die("Unexpected end of line")
    }
  }

  /**
    * Parses a DESCRIBE statement
    * @example {{{ DESCRIBE './companylist.csv' }}}
    * @example {{{ DESCRIBE './companylist.csv' LIMIT 5 }}}
    * @param ts the given [[TokenStream token stream]]
    * @return an [[Describe executable]]
    */
  private def parseDescribe(ts: TokenStream): Describe = {
    val params = SQLTemplateParser(ts).process("DESCRIBE @&source ?LIMIT ?@limit")
    Describe(
      source = params.sources.getOrElse("source", ts.die("No source provided")),
      limit = params.atoms.get("limit").map(parseInteger(_, "Numeric value expected for LIMIT")))
  }

  /**
    * Parses an INSERT statement
    * @example
    * {{{
    * INSERT INTO './tickers.csv' (symbol, exchange, lastSale)
    * VALUES ('AAPL', 'NASDAQ', 145.67)
    * VALUES ('AMD', 'NYSE', 5.66)
    * }}}
    * @example
    * {{{
    * INSERT OVERWRITE './companyinfo.csv' (Symbol, Name, Sector, Industry, LastSale, MarketCap)
    * SELECT Symbol, Name, Sector, Industry, LastSale, MarketCap
    * FROM './companylist.csv' WHERE Industry = 'EDP Services'
    * }}}
    * @example
    * {{{
    * INSERT INTO 'companylist.json' WITH FORMAT JSON (Symbol, Name, Sector, Industry)
    * SELECT Symbol, Name, Sector, Industry, `Summary Quote`
    * FROM 'companylist.csv' WITH FORMAT CSV
    * WHERE Industry = 'Oil/Gas Transmission'
    * }}}
    * @param stream the given [[TokenStream token stream]]
    * @return an [[Insert executable]]
    */
  private def parseInsert(stream: TokenStream): Insert = {
    val parser = SQLTemplateParser(stream)
    val params = parser.process("INSERT @|mode|INTO|OVERWRITE| @target") +
      parseWithClause(name = "hints", stream) + parser.process("( @(fields) )")
    val target = params.atoms.get("target").map(DataResource.apply(_, params.hints.get("hints")))
      .getOrElse(throw new SyntaxException("Output source is missing"))
    val fields = params.fields
      .getOrElse("fields", stream.die("Field arguments missing"))
    val append = params.atoms.get("mode").exists(_.equalsIgnoreCase("INTO"))
    // VALUES or SELECT
    val source = stream match {
      case ts if ts.is("VALUES") => parseInsertValues(fields, ts, parser)
      case ts => parseNext(ts)
    }
    Insert(target, fields, source, append)
  }

  /**
    * Parses WITH clauses
    * @example WITH CSV|PSV|TSV|JSON FORMAT
    * @example WITH DELIMITER ','
    * @example WITH QUOTED NUMBERS
    * @example WITH GZIP COMPRESSION
    * @param name   the name of the collection
    * @param stream the given [[TokenStream token stream]]
    */
  private def parseWithClause(name: String, stream: TokenStream) = {
    val withCompression = "WITH @|compression|GZIP| COMPRESSION"
    val withDelimiter = "WITH DELIMITER @delimiter"
    val withFormat = "WITH @|format|CSV|JSON|PSV|TSV| FORMAT"
    val withHeader = "WITH COLUMN @|column|HEADERS|"
    val withProps = "WITH PROPERTIES @props"
    val withQuoted = "WITH QUOTED @|quoted|NUMBERS|TEXT|"
    val parser = SQLTemplateParser(stream)
    var hints = Hints()

    def toOption(on: Boolean) = if (on) Some(true) else None

    while (stream.is("WITH")) {
      parser match {
        // WITH COLUMN [HEADERS]
        case p if p.matches(withHeader) =>
          val params = p.process(withHeader)
          hints = hints.copy(headers = params.atoms.get("column").map(_.equalsIgnoreCase("HEADERS")))
        // WITH [GZIP] COMPRESSION
        case p if p.matches(withCompression) =>
          val params = p.process(withCompression)
          hints = hints.copy(gzip = params.atoms.get("compression").map(_.equalsIgnoreCase("GZIP")))
        // WITH DELIMITER [,]
        case p if p.matches(withDelimiter) =>
          val params = p.process(withDelimiter)
          hints = hints.copy(delimiter = params.atoms.get("delimiter"))
        // WITH PROPERTIES [./myprops.properties]
        case p if p.matches(withProps) =>
          val params = p.process(withProps)
          hints = hints.copy(properties = params.atoms.get("props").map(getProperties))
        // WITH QUOTED [NUMBERS|TEXT]
        case p if p.matches(withQuoted) =>
          val params = p.process(withQuoted)
          hints = hints.copy(
            quotedNumbers = toOption(params.atoms.get("quoted").exists(_.equalsIgnoreCase("NUMBERS"))) ?? hints.quotedNumbers,
            quotedText = toOption(params.atoms.get("quoted").exists(_.equalsIgnoreCase("TEXT"))) ?? hints.quotedText)
        // WITH [CSV|PSV|TSV|JSON] FORMAT
        case p if p.matches(withFormat) =>
          val params = p.process(withFormat)
          params.atoms.get("format").foreach(format => hints = hints.usingFormat(format = format))
        case _ =>
          stream.die("Syntax error")
      }
    }
    SQLTemplateParams(hints = if (hints.nonEmpty) Map(name -> hints) else Map.empty)
  }

  private def getProperties(path: String): Properties = {
    val props = new Properties()
    props.load(new FileInputStream(path))
    props
  }

  /**
    * Parses an INSERT VALUES clause
    * @param fields the corresponding fields
    * @param ts     the [[TokenStream token stream]]
    * @param parser the implicit [[SQLTemplateParser template parser]]
    * @return the resulting [[InsertValues modifications]]
    */
  private def parseInsertValues(fields: Seq[Field], ts: TokenStream, parser: SQLTemplateParser): InsertValues = {
    val valueSets = parser.process("{{valueSet VALUES ( @{values} ) }}").repeatedSets.get("valueSet") match {
      case Some(sets) => sets.flatMap(_.expressions.get("values"))
      case None =>
        throw SyntaxException("VALUES clause could not be parsed", ts)
    }
    if (!valueSets.forall(_.size == fields.size))
      throw SyntaxException("The number of fields must match the number of values", ts)
    InsertValues(fields, valueSets)
  }

  /**
    * Parses a SELECT query
    * @example
    * {{{
    * SELECT symbol, exchange, lastSale FROM './EOD-20170505.txt'
    * WHERE exchange = 'NASDAQ'
    * LIMIT 5
    * }}}
    * @param ts the given [[TokenStream token stream]]
    * @return an [[Select executable]]
    */
  private def parseSelect(ts: TokenStream): Select = {
    val params = SQLTemplateParser(ts).process("SELECT ?TOP ?@top @{fields} ?FROM ?@&source") +
      parseWithClause(name = "hints", ts) +
      SQLTemplateParser(ts).process(
        """
          |?WHERE ?@!{condition}
          |?GROUP +?BY ?@(groupBy)
          |?ORDER +?BY ?@[orderBy]
          |?LIMIT ?@limit""".stripMargin.toSingleLine)

    Select(
      fields = params.expressions.getOrElse("fields", ts.die("Field arguments missing")),
      source = params.sources.get("source").map(_.withHints(params.hints.get("hints"))),
      condition = params.conditions.get("condition"),
      groupFields = params.fields.getOrElse("groupBy", Nil),
      orderedColumns = params.orderedFields.getOrElse("orderBy", Nil),
      limit = (params.atoms.get("limit") ?? params.atoms.get("top"))
        .map(parseInteger(_, "Numeric value expected LIMIT or TOP"))
    )
  }

  /**
    * Parses the given text value into an integer
    * @param value the given text value
    * @return an integer value
    */
  private def parseInteger(value: String, message: String): Int = {
    Try(value.toInt) match {
      case Success(limit) => limit
      case Failure(e) =>
        throw new SyntaxException(message, cause = e)
    }
  }

}
