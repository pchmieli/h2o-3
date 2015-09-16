package water.currents;

import water.Futures;
import water.Key;
import water.KeySnapshot;
import water.fvec.AppendableVec;
import water.fvec.Frame;
import water.fvec.NewChunk;
import water.fvec.Vec;

import java.util.ArrayList;

/**
* R 'ls' command.
*
* This method is purely for the console right now.  Print stuff into the string buffer.
* JSON response is not configured at all.
*/
class ASTLs extends ASTPrim {
  @Override
  public String[] args() { return null; }
  @Override int nargs() { return 1; }
  @Override String str() { return "ls" ; }
  @Override Val apply( Env env, Env.StackHelp stk, AST asts[] ) {
    ArrayList<String> domain = new ArrayList<>();
    Futures fs = new Futures();
    AppendableVec av = new AppendableVec(Vec.VectorGroup.VG_LEN1.addVec());
    NewChunk keys = new NewChunk(av,0);
    int r = 0;
    for( Key key : KeySnapshot.globalSnapshot().keys()) {
      keys.addEnum(r++);
      domain.add(key.toString());
    }
    keys.close(fs);
    Vec c0 = av.close(fs);   // c0 is the row index vec
    fs.blockForPending();
    String[] key_domain = new String[domain.size()];
    for (int i = 0; i < key_domain.length; ++i) key_domain[i] = domain.get(i);
    c0.setDomain(key_domain);
    
    return new ValFrame(new Frame(Key.make("h2o_ls"), new String[]{"key"}, new Vec[]{c0}));
  }
}

