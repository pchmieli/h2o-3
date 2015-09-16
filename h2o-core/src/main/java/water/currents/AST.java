package water.currents;

import water.Iced;
import water.fvec.Frame;

import java.util.HashMap;

/**
 * Abstract Syntax Tree
 *
 * Subclasses define the program semantics
 */
abstract public class AST extends Iced<AST> {
  // Subclasses define their execution.  Constants like Numbers & Strings just
  // return a ValXXX.  Constant functions also just return a ValFun.

  // ASTExec is Function application, and evaluates the 1st arg and calls
  // 'apply' to evaluate the remaining arguments.  Usually 'apply' is just
  // "exec all args" then apply a primitive function op to the args, but for
  // logical AND/OR and IF statements, one or more arguments may never be
  // evaluated (short-circuit evaluation).
  public abstract Val exec(Env env);

  // Default action after the initial execution of a function.  Typically the
  // action is "execute all arguments, then apply a primitive action to the
  // arguments", but short-circuit evaluation may not execute all args.
  Val apply( Env env, Env.StackHelp stk, AST asts[] ) { throw water.H2O.fail(); }

  // Short name (there's lots of the simple math primtives, and we want them to
  // fit on one line)
  abstract String str();
  @Override public String toString() { return str(); }

  // Number of arguments, if that makes sense.  Always count 1 for self, so a
  // binary operator like '+' actually has 3 nargs.
  abstract int nargs();

  // Built-in primitives, done after other namespace lookups happen
  static final HashMap<String,AST> PRIMS = new HashMap<>();
  static void init(AST ast) { PRIMS.put(ast.str(),ast); }
  static {
    // Constants
    init(new ASTNum(0) {public String str() { return "FALSE"; } } );
    init(new ASTNum(1) {public String str() { return "TRUE" ; } } );
    init(new ASTNum(Double.NaN) { public String str() { return "NaN";} } );
    init(new ASTNum(Double.NaN) { public String str() { return "NA";} } );

    // Math unary ops
    init(new ASTACos  ());
    init(new ASTACosh());
    init(new ASTASin());
    init(new ASTASinh());
    init(new ASTATan());
    init(new ASTATanh());
    init(new ASTAbs   ());
    init(new ASTCeiling());
    init(new ASTCos   ());
    init(new ASTCosPi());
    init(new ASTCosh  ());
    init(new ASTDiGamma());
    init(new ASTExp   ());
    init(new ASTExpm1());
    init(new ASTFloor ());
    init(new ASTGamma());
    init(new ASTIsCharacter());
    init(new ASTIsNA  ());
    init(new ASTIsNumeric());
    init(new ASTLGamma());
    init(new ASTLevels());
    init(new ASTLog   ());
    init(new ASTLog10());
    init(new ASTLog1p());
    init(new ASTLog2());
    init(new ASTNLevels());
    init(new ASTNcol  ());
    init(new ASTNot   ());
    init(new ASTNrow  ());
    init(new ASTPop());
    init(new ASTRound ());
    init(new ASTSgn());
    init(new ASTSignif());
    init(new ASTSin   ());
    init(new ASTSinPi());
    init(new ASTSinh());
    init(new ASTSqrt  ());
    init(new ASTTan   ());
    init(new ASTTanPi());
    init(new ASTTanh  ());
    init(new ASTTriGamma());
    init(new ASTTrunc ());

    // Math binary ops
    init(new ASTAnd ());
    init(new ASTDiv ());
    init(new ASTIntDiv());
    init(new ASTMod ());
    init(new ASTMul ());
    init(new ASTOr  ());
    init(new ASTPlus());
    init(new ASTPow ());
    init(new ASTScale());
    init(new ASTSub ());

    // Relational
    init(new ASTGE());
    init(new ASTGT());
    init(new ASTLE());
    init(new ASTLT());
    init(new ASTEQ());
    init(new ASTNE());

    // Logical - includes short-circuit evaluation
    init(new ASTLAnd());
    init(new ASTLOr());
    init(new ASTIfElse());

    // Reducers
    init(new ASTAll());
    init(new ASTAny());
    init(new ASTAnyNA());
    init(new ASTCumMax());
    init(new ASTCumMin());
    init(new ASTCumProd());
    init(new ASTCumSum());
    init(new ASTMax());
    init(new ASTMaxNA());
    init(new ASTMean());
    init(new ASTMedian());
    init(new ASTMin());
    init(new ASTMinNA());
    init(new ASTProd());
    init(new ASTProdNA());
    init(new ASTSdev());
    init(new ASTSum());
    init(new ASTSumNA());

    // Time
    init(new ASTDay());
    init(new ASTDay());
    init(new ASTDayOfWeek());
    init(new ASTGetTimeZone());
    init(new ASTHour());
    init(new ASTListTimeZones());
    init(new ASTMillis());
    init(new ASTMinute());
    init(new ASTMktime());
    init(new ASTMonth());
    init(new ASTSecond());
    init(new ASTSetTimeZone());
    init(new ASTWeek());
    init(new ASTYear());
    init(new ASTasDate());

    // Complex Math
    init(new ASTHist());
    init(new ASTImpute());
    init(new ASTMode());
    init(new ASTQtile());
    init(new ASTRunif());
    init(new ASTStratifiedSplit());
    init(new ASTTable());
    init(new ASTUnique());
    init(new ASTVariance());

    // Generic data mungers
    init(new ASTAnyFactor());
    init(new ASTAsFactor());
    init(new ASTCharacter());
    init(new ASTAsNumeric());
    init(new ASTCBind());
    init(new ASTColNames());
    init(new ASTColSlice());
    init(new ASTFilterNACols());
    init(new ASTFlatten());
    init(new ASTIsFactor());
    init(new ASTAnyFactor());
    init(new ASTRBind());
    init(new ASTRowSlice());
    init(new ASTSetDomain());
    init(new ASTSetLevel());
    init(new ASTTmpAssign());

    // Matrix Ops
    init(new ASTTranspose());
    init(new ASTMMult());

    // Complex data mungers
    init(new ASTAssign());
    init(new ASTCut());
    init(new ASTDdply());
    init(new ASTGroup());
    init(new ASTMerge());
    init(new ASTQtile());

    // String Ops
    init(new ASTStrSplit());
    init(new ASTStrSub());
    init(new ASTGSub());
    init(new ASTTrim());
    init(new ASTToLower());
    init(new ASTCountMatches());
    init(new ASTToUpper());

    // Functional data mungers
    init(new ASTApply());
    init(new ASTComma());

    // Cluster management
    init(new ASTLs());

    // Search
    init(new ASTMatch());
    init(new ASTWhich());

    // Repeaters
    init(new ASTRepLen());
    init(new ASTSeq());
    init(new ASTSeqLen());

    // KFoldColumns
    init(new ASTKFold());
    init(new ASTModuloKFold());
    init(new ASTStratifiedKFold());
  }

  public static ASTFrame newASTFrame(Frame f){ return new ASTFrame(f); }
}

/** A number.  Execution is just to return the constant. */
class ASTNum extends AST {
  final ValNum _d;
  ASTNum( Exec e ) { _d = new ValNum(Double.valueOf(e.token())); }
  ASTNum( double d ) { _d = new ValNum(d); }
  @Override public String str() { return _d.toString(); }
  @Override public Val exec(Env env) { return _d; }
  @Override int nargs() { return 1; }
}

/** A String.  Execution is just to return the constant. */
class ASTStr extends AST {
  final ValStr _str;
  ASTStr(String str) { _str = new ValStr(str); }
  ASTStr(Exec e, char c) { _str = new ValStr(e.match(c)); }
  @Override public String str() { return _str.toString().replaceAll("^\"|^\'|\"$|\'$",""); }
  @Override public Val exec(Env env) { return _str; }
  @Override int nargs() { return 1; }
}

/** A Frame.  Execution is just to return the constant. */
class ASTFrame extends AST {
  final ValFrame _fr;
  ASTFrame(Frame fr) { _fr = new ValFrame(fr); }
  @Override public String str() { return _fr.toString(); }
  @Override public Val exec(Env env) { return _fr; }
  @Override int nargs() { return 1; }
}

/** A Row.  Execution is just to return the constant. */
class ASTRow extends AST {
  final ValRow _row;
  ASTRow(double[] ds) { _row = new ValRow(ds); }
  @Override public String str() { return _row.toString(); }
  @Override public ValRow exec(Env env) { return _row; }
  @Override int nargs() { return 1; }
}

/** An ID.  Execution does lookup in the current scope. */
class ASTId extends AST {
  final String _id;
  ASTId(Exec e) { _id = e.token(); }
  @Override public String str() { return _id; }
  @Override public Val exec(Env env) { return env.lookup(_id); }
  @Override int nargs() { return 1; }
}

/** A primitive operation.  Execution just returns the function.  *Application*
 *  (not execution) applies the function to the arguments. */
abstract class ASTPrim extends AST {
  @Override public Val exec(Env env) { return new ValFun(this); }
  public abstract String[] args();
}
