package water.persist;

import java.io.*;
import java.net.URI;
import java.util.ArrayList;

import water.*;
import water.api.FSIOException;
import water.fvec.NFSFileVec;
import water.util.Log;

/**
 * Persistence backend using local file system.
 */
final class PersistFS extends Persist {
  final File _root;
  final File _dir;

  PersistFS(File root) {
    _root = root;
    _dir = new File(root, "ice" + H2O.API_PORT);
    //deleteRecursive(_dir);
    // Make the directory as-needed
    root.mkdirs();
    if( !(root.isDirectory() && root.canRead() && root.canWrite()) )
      H2O.die("ice_root not a read/writable directory");
  }

  public void cleanUp() { deleteRecursive(_dir); }

  private static void deleteRecursive(File path) {
    if( !path.exists() ) return;
    if( path.isDirectory() )
      for (File f : path.listFiles())
        deleteRecursive(f);
    path.delete();
  }

  private File getFile(Value v) {
    return new File(_dir, getIceName(v));
  }

  @Override public byte[] load(Value v) throws IOException {
    File f = getFile(v);
    if( f.length() < v._max ) { // Should be fully on disk...
      // or it's a racey delete of a spilled value
      assert !v.isPersisted() : f.length() + " " + v._max + " " + v._key;
      return null; // No value
    }
    try (FileInputStream s = new FileInputStream(f)) {
        AutoBuffer ab = new AutoBuffer(s.getChannel(), true, Value.ICE);
        byte[] b = ab.getA1(v._max);
        ab.close();
        return b;
      }
  }

  // Store Value v to disk.
  @Override public void store(Value v) {
    assert !v.isPersisted();
    new File(_dir, getIceDirectory(v._key)).mkdirs();
    // Nuke any prior file.
    FileOutputStream s = null;
    try { s = new FileOutputStream(getFile(v)); }
    catch( FileNotFoundException e ) { throw Log.throwErr(e); }
    try {
      byte[] m = v.memOrLoad(); // we are not single threaded anymore
      if( m != null && m.length == v._max ) {
        Log.warn("Value size mismatch? " + v._key + " byte[].len=" + m.length+" v._max="+v._max);
        v._max = m.length; // Implies update of underlying POJO, then re-serializing it without K/V storing it
      }
      new AutoBuffer(s.getChannel(), false, Value.ICE).putA1(m, m.length).close();
      v.setdsk();             // Set as write-complete to disk
    } finally {
      if( s!=null ) try { s.close(); } catch( IOException ie ) { }
    }
  }

  @Override public void delete(Value v) {
    assert !v.isPersisted();   // Upper layers already cleared out
    File f = getFile(v);
    f.delete();
    if( v.isVec() ) { // Also nuke directory if the top-level Vec dies
      f = new File(_dir.toString(), getIceDirectory(v._key));
      f.delete();
    }
  }

  @Override public long getUsableSpace() {
    return _root.getUsableSpace();
  }

  @Override public long getTotalSpace() {
    return _root.getTotalSpace();
  }

  @Override
  public Key uriToKey(URI uri) {
    return NFSFileVec.make(new File(uri.toString()))._key;
  }

  @Override
  public ArrayList<String> calcTypeaheadMatches(String src, int limit) {
    assert false;
    return new ArrayList<>();
  }

  @Override
  public void importFiles(String path, ArrayList<String> files, ArrayList<String> keys, ArrayList<String> fails, ArrayList<String> dels) {
    assert false;
  }

  @Override
  public OutputStream create(String path, boolean overwrite) {
    File f = new File(URI.create(path));
    if (f.exists() && !overwrite)
      throw new FSIOException(path, "File already exists");

    try {
      return new FileOutputStream(f, false);
    } catch (IOException e) {
      throw new FSIOException(path, e);
    }
  }

  @Override
  public PersistEntry[] list(String path) {
    File f = new File(URI.create(path));
    if (f.isFile()) {
      return new PersistEntry[] { getPersistEntry(f) };
    } else if (f.isDirectory()) {
      File[] files = f.listFiles();
      PersistEntry[] entries = new PersistEntry[files.length];
      for (int i = 0; i < files.length; i++) {
        entries[i] = getPersistEntry(files[i]);
      }
      return entries;
    }

    throw H2O.unimpl();
  }

  @Override
  public InputStream open(String path) {
    try {
      File f = new File(URI.create(path));
      return new FileInputStream(f);
    } catch (FileNotFoundException e) {
      throw new FSIOException(path, "File not found");
    } catch (Exception e) {
      throw new FSIOException(path, e);
    }
  }

  @Override
  public boolean mkdirs(String path) {
    return new File(URI.create(path)).mkdirs();
  }

  @Override
  public boolean exists(String path) {
    return new File(URI.create(path)).exists();
  }

  private PersistEntry getPersistEntry(File f) {
    return new PersistEntry(f.getName(), f.length(), f.lastModified());
  }
}
