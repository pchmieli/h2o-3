package water.api;

import water.Iced;
import water.fvec.Frame;

class RapidsSchema<I extends Iced,R extends RapidsSchema<I,R>> extends Schema<I, R> {
  // Input fields
  @API(help="An Abstract Syntax Tree.", direction=API.Direction.INPUT) String ast;
  @API(help="Key name to assign Frame results", direction=API.Direction.INPUT) String id;
}

class RapidsScalarV3 extends RapidsSchema<Iced,RapidsScalarV3> {
  @API(help="Scalar result"          , direction=API.Direction.OUTPUT) double scalar;
  RapidsScalarV3( ) { }
  RapidsScalarV3( double d ) { scalar = d; }
}

class RapidsStringV3 extends RapidsSchema<Iced,RapidsStringV3> {
  @API(help="String result"          , direction=API.Direction.OUTPUT) String scalar;
  RapidsStringV3( ) { }
  RapidsStringV3( String s ) { scalar = s; }
}

class RapidsFunctionV3 extends RapidsSchema<Iced,RapidsFunctionV3> {
  @API(help="Function result"          , direction=API.Direction.OUTPUT) String funstr;
  RapidsFunctionV3( ) { }
  RapidsFunctionV3( String s ) { funstr = s; }
}

class RapidsFrameV3 extends RapidsSchema<Iced,RapidsFrameV3> {
  @API(help="Frame result"          , direction=API.Direction.OUTPUT) KeyV3.FrameKeyV3 key;
  @API(help="Rows in Frame result"   , direction=API.Direction.OUTPUT) long num_rows;
  @API(help="Columns in Frame result", direction=API.Direction.OUTPUT) int  num_cols;
  RapidsFrameV3( ) { }
  RapidsFrameV3( Frame fr ) { key = new KeyV3.FrameKeyV3(fr._key); num_rows = fr.numRows(); num_cols = fr.numCols(); }
}
