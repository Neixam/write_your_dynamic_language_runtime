package fr.umlv.smalljs.astinterp;

import fr.umlv.smalljs.ast.Expr;
import fr.umlv.smalljs.ast.Expr.Block;
import fr.umlv.smalljs.ast.Expr.FieldAccess;
import fr.umlv.smalljs.ast.Expr.FieldAssignment;
import fr.umlv.smalljs.ast.Expr.Fun;
import fr.umlv.smalljs.ast.Expr.FunCall;
import fr.umlv.smalljs.ast.Expr.If;
import fr.umlv.smalljs.ast.Expr.Literal;
import fr.umlv.smalljs.ast.Expr.LocalVarAccess;
import fr.umlv.smalljs.ast.Expr.LocalVarAssignment;
import fr.umlv.smalljs.ast.Expr.MethodCall;
import fr.umlv.smalljs.ast.Expr.New;
import fr.umlv.smalljs.ast.Expr.Return;
import fr.umlv.smalljs.ast.Script;
import fr.umlv.smalljs.rt.Failure;
import fr.umlv.smalljs.rt.JSObject;
import fr.umlv.smalljs.rt.JSObject.Invoker;

import java.io.PrintStream;
import java.io.StringReader;
import java.util.*;
import java.util.stream.IntStream;

import static fr.umlv.smalljs.ast.ASTBuilder.createScript;
import static fr.umlv.smalljs.rt.JSObject.UNDEFINED;
import static java.util.stream.Collectors.joining;

public class ASTInterpreter {
  private static JSObject asJSObject(Object value, int lineNumber) {
    if (!(value instanceof JSObject jsObject)) {
      throw new Failure("at line " + lineNumber + ", type error " + value + " is not a JSObject");
    }
    return jsObject;
  }

  static Object visit(Expr expression, JSObject env) {
    return switch (expression) {
      case Block(List<Expr> instrs, int lineNumber) -> {
        instrs.forEach(instr -> visit(instr, env));
        yield UNDEFINED;
      }
      case Literal<?>(Object value, int lineNumber) -> {
        yield value;
      }
      case FunCall(Expr qualifier, List<Expr> args, int lineNumber) -> {
        var function = asJSObject(visit(qualifier, env), lineNumber);
        yield function.invoke(UNDEFINED, args.stream().map(arg -> visit(arg, env)).toArray());
      }
      case LocalVarAccess(String name, int lineNumber) -> {
        yield env.lookup(name);
      }
      case LocalVarAssignment(String name, Expr expr, boolean declaration, int lineNumber) -> {
        if (declaration) {
          if (env.lookup(name) != UNDEFINED) {
            throw new Failure(name + " has already declared at " + lineNumber);
          }
        }
        var value = visit(expr, env);
        env.register(name, value);
        yield value;
      }
      case Fun(Optional<String> optName, List<String> parameters, Block body, int lineNumber) -> {
        var functionName = optName.orElse("lambda");
        var invoker = (Invoker) (self, receiver, args) -> {
          // check the arguments length
          if (args.length != parameters.size()) {
            throw new Failure(functionName +
                    " not good invoke of function at " +
                    lineNumber + " you call with " +
                    args.length + " arguments but hit take " +
                    parameters.size());
          }
          // create a new environment
          var newEnv = JSObject.newEnv(env);
          // add this and all the parameters
          newEnv.register("this", receiver);
          IntStream.range(0, args.length).forEach(i -> newEnv.register(parameters.get(i), args[i]));
          // visit the body
          try {
            return visit(body, newEnv);
          } catch (ReturnError ret) {
            return ret.getValue();
          }
        };
        // create the JS function with the invoker
        var functionDeclaration = JSObject.newFunction(functionName, invoker);
        // register it if necessary
        env.register(functionName, functionDeclaration);
        // yield the function
        yield functionDeclaration;
      }
      case Return(Expr expr, int lineNumber) -> {
        throw new ReturnError(visit(expr, env));
      }
      case If(Expr condition, Block trueBlock, Block falseBlock, int lineNumber) -> {
        if (Objects.equals(0, visit(condition, env))) {
          yield visit(falseBlock, env);
        } else {
          yield visit(trueBlock, env);
        }
      }
      case New(Map<String, Expr> initMap, int lineNumber) -> {
        var newObj = JSObject.newObject(null);
        initMap.forEach((name, value) -> newObj.register(name, visit(value, env)));
        yield newObj;
      }
      case FieldAccess(Expr receiver, String name, int lineNumber) -> {
        var object = asJSObject(visit(receiver, env), lineNumber);
        yield object.lookup(name);
      }
      case FieldAssignment(Expr receiver, String name, Expr expr, int lineNumber) -> {
        var object = asJSObject(visit(receiver, env), lineNumber);
        if (object.lookup(name) == UNDEFINED) {
          throw new Failure("");
        }
        var newValue = visit(expr, env);
        object.register(name, newValue);
        yield newValue;
      }
      case MethodCall(Expr receiver, String name, List<Expr> args, int lineNumber) -> {
        var object = asJSObject(visit(receiver, env), lineNumber);
        var method = asJSObject(object.lookup(name), lineNumber);
        yield method.invoke(object, args.stream().map(arg -> visit(arg, env)).toArray());
      }
    };
  }

  @SuppressWarnings("unchecked")
  public static void interpret(Script script, PrintStream outStream) {
    JSObject globalEnv = JSObject.newEnv(null);
    Block body = script.body();
    globalEnv.register("global", globalEnv);
    globalEnv.register("print", JSObject.newFunction("print", (self, receiver, args) -> {
      System.err.println("print called with " + Arrays.toString(args));
      outStream.println(Arrays.stream(args).map(Object::toString).collect(joining(" ")));
      return UNDEFINED;
    }));
    globalEnv.register("+", JSObject.newFunction("+", (self, receiver, args) -> (Integer) args[0] + (Integer) args[1]));
    globalEnv.register("-", JSObject.newFunction("-", (self, receiver, args) -> (Integer) args[0] - (Integer) args[1]));
    globalEnv.register("/", JSObject.newFunction("/", (self, receiver, args) -> (Integer) args[0] / (Integer) args[1]));
    globalEnv.register("*", JSObject.newFunction("*", (self, receiver, args) -> (Integer) args[0] * (Integer) args[1]));
    globalEnv.register("%", JSObject.newFunction("%", (self, receiver, args) -> (Integer) args[0] % (Integer) args[1]));

    globalEnv.register("==", JSObject.newFunction("==", (self, receiver, args) -> args[0].equals(args[1]) ? 1 : 0));
    globalEnv.register("!=", JSObject.newFunction("!=", (self, receiver, args) -> !args[0].equals(args[1]) ? 1 : 0));
    globalEnv.register("<", JSObject.newFunction("<", (self, receiver, args) -> (((Comparable<Object>) args[0]).compareTo(args[1]) < 0) ? 1 : 0));
    globalEnv.register("<=", JSObject.newFunction("<=", (self, receiver, args) -> (((Comparable<Object>) args[0]).compareTo(args[1]) <= 0) ? 1 : 0));
    globalEnv.register(">", JSObject.newFunction(">", (self, receiver, args) -> (((Comparable<Object>) args[0]).compareTo(args[1]) > 0) ? 1 : 0));
    globalEnv.register(">=", JSObject.newFunction(">=", (self, receiver, args) -> (((Comparable<Object>) args[0]).compareTo(args[1]) >= 0) ? 1 : 0));
    visit(body, globalEnv);
  }
}

