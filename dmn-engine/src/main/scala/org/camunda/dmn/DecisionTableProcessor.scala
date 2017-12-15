package org.camunda.dmn

import scala.collection.JavaConverters._

import org.camunda.dmn.DmnEngine._
import org.camunda.dmn.FunctionalHelper._
import org.camunda.feel._
import org.camunda.feel.interpreter.RootContext
import org.camunda.bpm.model.dmn._
import org.camunda.bpm.model.dmn.instance.{ Decision, DecisionTable, InputEntry, OutputEntry, Output, Rule, Input }

class DecisionTableProcessor(val feelEngine: FeelEngine) extends ExpressionProcessor {

  def eval(decisionTable: DecisionTable)(implicit context: EvalContext): Either[Failure, Option[Any]] = {

    val inputs = decisionTable.getInputs.asScala

    evalInputExpressions(inputs).right.flatMap { inputValues =>

      val rules = decisionTable.getRules.asScala

      checkRules(rules, inputValues).right.map(_.flatten).flatMap { matchedRules =>

        val outputs = decisionTable.getOutputs.asScala.toList

        matchedRules match {
          case Nil => applyDefaultOutputEntries(outputs)
          case _ => {

            val hitPolicy = decisionTable.getHitPolicy
            val aggregation = decisionTable.getAggregation

            val rules = if (hitPolicy == HitPolicy.FIRST) List(matchedRules.head) else matchedRules

            evalOutputValues(rules, outputs).right.flatMap(values =>
              applyHitPolicy(hitPolicy, aggregation, outputs, values))
          }
        }
      }
    }
  }
  
  private def evalInputExpressions(inputs: Iterable[Input])(implicit context: EvalContext): Either[Failure, List[Any]] = { 
    mapEither(inputs, (input: Input) => {
      
      val expr = input.getInputExpression.getText.getTextContent
      val expression = context.parsedExpressions(expr)

      evalExpression(expression, context.variables)
    })
  }
  
  private def checkRules(rules: Iterable[Rule], inputValues: List[Any])(implicit context: EvalContext): Either[Failure, List[Option[Rule]]] = {
    mapEither(rules, (rule: Rule) => {
        
        isMet(rule, inputValues)
          .right
          .map(isMet => if (isMet) Some(rule) else None)
      })
  }
  
  private def isMet(rule: Rule, inputValues: List[Any])(implicit context: EvalContext): Either[Failure, Boolean] = {
    
    val inputEntries = rule.getInputEntries.asScala.toList
    
    evalInputEntries(inputEntries.zip(inputValues))
  }

  private def evalInputEntries(inputEntries: List[(InputEntry, Any)])(implicit context: EvalContext): Either[Failure, Boolean] = {

    inputEntries match {
      case Nil => Right(true)
      case (entry, value) :: is => {

        val expression = context.parsedExpressions(entry.getText.getTextContent)

        evalInputEntry(expression, value)
          .right
          .flatMap { result =>
            result match {
              case false => Right(false)
              case true => evalInputEntries(is)
              case other => Left(Failure(s"input entry must return true or false, but found '$other'"))
            }
          }
      }
    }
  }
  
  private def evalInputEntry(expression: ParsedExpression, inputValue: Any)(implicit context: EvalContext): Either[Failure, Any] = {
    
    val variablesWithInput = context.variables + (RootContext.defaultInputVariable -> inputValue)
    
    evalExpression(expression, variablesWithInput)
  }

  private def applyDefaultOutputEntries(outputs: Iterable[Output])(implicit context: EvalContext): Either[Failure, Option[Any]] = {

    evalDefaultOutputEntries(outputs)
      .right
      .map(_.flatten)
      .map(_.toMap)
      .map { outputValues =>

        if (outputValues.isEmpty) {
          None
        } else if (outputValues.size == 1) {
          outputValues.values.headOption
        } else {
          Some(outputValues)
        }
      }
  }

  private def evalDefaultOutputEntries(outputs: Iterable[Output])(implicit context: EvalContext): Either[Failure, List[Option[(String, Any)]]] = {
    mapEither(outputs, (output: Output) => {

      val outputValue = Option(output.getDefaultOutputEntry).map { defaultOutput =>

        val expression = context.parsedExpressions(defaultOutput.getText.getTextContent)

        evalExpression(expression, context.variables)
          .right
          .map(r => Some(output.getName -> r))
      }

      outputValue.getOrElse(Right(None))
    })
  }

  private def evalOutputValues(rules: Iterable[Rule], outputs: Iterable[Output])(implicit context: EvalContext): Either[Failure, List[Map[String, Any]]] = {

    mapEither(rules, (rule: Rule) => {

      val outputEntries = rule.getOutputEntries.asScala.toList

      val values = mapEither(outputEntries, (entry: OutputEntry) => {
        
        val expression = context.parsedExpressions(entry.getText.getTextContent)

        evalExpression(expression, context.variables)
      })

      values.right.map(outputValues => {
        outputs
          .map(_.getName)
          .zip(outputValues)
          .toMap
      })
    })
  }

  private def applyHitPolicy(
    hitPolicy: HitPolicy,
    aggregator: BuiltinAggregator,
    outputs: List[Output],
    outputValues: List[Map[String, Any]]): Either[Failure, Option[Any]] = {

    Option(hitPolicy).getOrElse(HitPolicy.UNIQUE) match {

      case HitPolicy.FIRST => Right(singleOutputValue(outputValues))
      
      case HitPolicy.UNIQUE => { 
        
        if (outputValues.isEmpty || outputValues.size == 1) {
          Right(singleOutputValue(outputValues))
        } else {
          Left(Failure(s"multiple values aren't allowed for UNIQUE hit policy. found: '$outputValues'"))
        }
      }
      
      case HitPolicy.ANY => {
        
        val disinctValues = outputValues.distinct
        
        if (disinctValues.isEmpty || disinctValues.size == 1) {
          Right(singleOutputValue(outputValues))
        } else {
          Left(Failure(s"different values aren't allowed for ANY hit policy. found: '$disinctValues'"))
        }
      }
      
      case HitPolicy.PRIORITY => Right(singleOutputValue(sortByPriority(outputValues, outputs)))

      case HitPolicy.OUTPUT_ORDER => Right(multipleOutputValues(sortByPriority(outputValues, outputs)))
      
      case HitPolicy.RULE_ORDER => Right(multipleOutputValues(outputValues))

      case HitPolicy.COLLECT => aggregator match {
        case BuiltinAggregator.MIN   => singleNumberValues(outputValues).right.map(l => Some(l.min))
        case BuiltinAggregator.MAX   => singleNumberValues(outputValues).right.map(l => Some(l.max))
        case BuiltinAggregator.SUM   => singleNumberValues(outputValues).right.map(l => Some(l.sum))
        case BuiltinAggregator.COUNT => Right(Some(outputValues.size))
        case _ => Right(multipleOutputValues(outputValues))
      }
    }
  }

  private def singleOutputValue(values: List[Map[String, Any]]): Option[Any] = {
    values
      .headOption
      .map(v => if (v.size == 1) v.values.head else v)
  }

  private def multipleOutputValues(values: List[Map[String, Any]]): Option[Any] = values match {
    case Nil => None
    case v :: Nil if (v.size == 1) => Some(v.values.head)
    case v :: Nil => Some(v)
    case list if (list.head.size == 1) => Some(list.map(_.values.head))
    case list => Some(list)
  }

  private def sortByPriority(outputValues: List[Map[String, Any]], outputs: List[Output]): List[Map[String, Any]] = {

    val priorities: List[(String, Map[String, Int])] = outputs.map { output =>

      val values = Option(output.getOutputValues)
        .map(_.getText.getTextContent)
        .map(_.split(",").map(_.trim))
        .map(_.toList)
        .getOrElse(List())

      output.getName -> values.zipWithIndex.toMap
    }

    outputValues.sortBy { values =>

      val valuePriorities = priorities.map {
        case (output, priority) =>
          val value = values(output).toString

          priority
            .get(value)
            .map(_.toString)
            .getOrElse("")
      }

      valuePriorities.reduce(_ + _)
    }
  }
    
  private def singleNumberValues(values: List[Map[String, Any]]): Either[Failure, List[Number]] = values match {
    case Nil => Right(Nil)
    case list if (list.head.size == 1) => mapEither(list.map(_.values.head), numberValue)
    case list => Left(Failure(s"multiple values aren't allowed. found: $list"))
  }

  private def numberValue(value: Any): Either[Failure, Number] = value match {
    case n: Number => Right(n)
    case o => Left(Failure(s"expected number but found '$o'"))
  }

}