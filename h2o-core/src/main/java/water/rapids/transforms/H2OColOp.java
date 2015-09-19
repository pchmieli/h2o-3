package water.rapids.transforms;

import org.apache.commons.lang.ArrayUtils;
import water.DKV;
import water.H2O;
import water.fvec.Frame;
import water.rapids.AST;
import water.rapids.ASTExec;
import water.rapids.ASTParameter;
import water.rapids.Exec;

public class H2OColOp extends Transform<H2OColOp> {
  final String _fun;
  String _oldCol;
  String _newCol;

  public H2OColOp(String name, String ast, boolean inplace) { // (op (cols fr cols) {extra_args})
    super(name,ast,inplace);
    _fun = _ast._asts[0].str();
    _oldCol = ((ASTExec)_ast._asts[1])._asts[2].str();
    String[] args = _ast.getArgs();
    if( args!=null && args.length > 1 ) { // first arg is the frame
      for(int i=1; i<args.length; ++i)
        _params.put(args[i],(ASTParameter)_ast._asts[i+1]);
    }
  }

  @Override public Transform<H2OColOp> fit(Frame f) { return this; }
  @Override protected Frame transformImpl(Frame f) {
    ((ASTExec)_ast._asts[1])._asts[1] = AST.newASTFrame(f);
    Frame fr = Exec.execute(_ast).getFrame();
    _newCol = _inplace?_oldCol:f.uniquify(_oldCol);
    if( _inplace ) f.replace(f.find(_oldCol), fr.anyVec()).remove();
    else           f.add(_newCol,fr.anyVec());
    DKV.put(f);
    return f;
  }

  @Override Frame inverseTransform(Frame f) { throw H2O.unimpl(); }

  @Override public String genClassImpl() {
    String typeCast = _inTypes[ArrayUtils.indexOf(_inNames, _oldCol)].equals("Numeric")?"double":"String";
    return  "    @Override public RowData transform(RowData row) {\n" +
            "      row.put(\""+_newCol+"\", GenMunger."+_fun+"(("+typeCast+")row.get(\""+_oldCol+"\"), _params));\n" +
            "      return row;\n" +
            "    }\n";
  }
}
