package water.rapids.transforms;

import water.DKV;
import water.H2O;
import water.Key;
import water.rapids.AST;
import water.rapids.Exec;
import water.fvec.Frame;

public class H2OColSelect extends Transform<H2OColSelect> {
  private final String _cols;

  public H2OColSelect(String name, String ast, boolean inplace) {  // not a public constructor -- used by the REST api only;
    super(name,ast,inplace);
    _cols = _ast._asts[2].toString(); //.substring(1,);
  }

  @Override Transform<H2OColSelect> fit(Frame f) { return this; }
  @Override Frame transform(Frame f) {
    _ast._asts[1] = AST.newASTFrame(f);
    Frame fr = Exec.execute(_ast).getFrame();
    if( fr._key==null ) fr = new Frame(Key.make("H2OColSelect_"+f._key.toString()),fr.names(),fr.vecs());
    DKV.put(fr);
    return fr;
  }
  @Override Frame inverseTransform(Frame f) { throw H2O.unimpl(); }
  public StringBuilder genClass() {
    String stepName = name();
    StringBuilder sb = new StringBuilder();
    String s = "public static class " + stepName + " extends Step<" + stepName + "> {\n" +
            "  private final String[] _cols = new String[]{"+ _cols +"};\n" +
            "  " + stepName + "() { _inplace=true; }\n" +
            "  @Override public RowData transform(RowData row) {\n" +
            "    RowData colSelect = new RowData();\n" +
            "    for(String s: _cols) \n" +
            "      colSelect.put(s, row.get(s));\n" +
            "    return colSelect;\n"+
            "  }\n"+
            "}\n";
    sb.append(s);
    return sb;
  }
}
