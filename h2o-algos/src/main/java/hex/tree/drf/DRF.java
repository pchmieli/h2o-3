package hex.tree.drf;

import hex.Distribution;
import hex.ModelCategory;
import hex.schemas.DRFV3;
import hex.tree.*;
import hex.tree.DTree.DecidedNode;
import hex.tree.DTree.LeafNode;
import hex.tree.DTree.UndecidedNode;
import water.AutoBuffer;
import water.Job;
import water.Key;
import water.MRTask;
import water.fvec.Chunk;
import water.fvec.Frame;
import water.util.Log;
import water.util.Timer;

import java.util.Arrays;
import java.util.Random;

import static hex.genmodel.GenModel.getPrediction;
import static hex.tree.drf.TreeMeasuresCollector.asSSE;
import static hex.tree.drf.TreeMeasuresCollector.asVotes;

/** Gradient Boosted Trees
 *
 *  Based on "Elements of Statistical Learning, Second Edition, page 387"
 */
public class DRF extends SharedTree<hex.tree.drf.DRFModel, hex.tree.drf.DRFModel.DRFParameters, hex.tree.drf.DRFModel.DRFOutput> {
  protected int _mtry;

  @Override public ModelCategory[] can_build() {
    return new ModelCategory[]{
      ModelCategory.Regression,
      ModelCategory.Binomial,
      ModelCategory.Multinomial,
    };
  }

  @Override public BuilderVisibility builderVisibility() { return BuilderVisibility.Stable; };

  // Called from an http request
  public DRF( hex.tree.drf.DRFModel.DRFParameters parms) { super("DRF",parms); init(false); }

  @Override public DRFV3 schema() { return new DRFV3(); }

  /** Start the DRF training Job on an F/J thread.
   * @param work
   * @param restartTimer*/
  @Override protected Job<hex.tree.drf.DRFModel> trainModelImpl(long work, boolean restartTimer) {
    return start(new DRFDriver(), work, restartTimer);
  }


  /** Initialize the ModelBuilder, validating all arguments and preparing the
   *  training frame.  This call is expected to be overridden in the subclasses
   *  and each subclass will start with "super.init();".  This call is made
   *  by the front-end whenever the GUI is clicked, and needs to be fast;
   *  heavy-weight prep needs to wait for the trainModel() call.
   */
  @Override public void init(boolean expensive) {
    super.init(expensive);
    // Initialize local variables
    if (!(0.0 < _parms._sample_rate && _parms._sample_rate <= 1.0))
      throw new IllegalArgumentException("Sample rate should be interval [0,1] but it is " + _parms._sample_rate);
    if( _parms._mtries < 1 && _parms._mtries != -1 ) error("_mtries", "mtries must be -1 (converted to sqrt(features)), or >= 1 but it is " + _parms._mtries);
    if( _train != null ) {
      int ncols = _train.numCols();
      if( _parms._mtries != -1 && !(1 <= _parms._mtries && _parms._mtries < ncols))
        error("_mtries","Computed mtries should be -1 or in interval [1,"+ncols+"] but it is " + _parms._mtries);
    }
    if (_parms._distribution == Distribution.Family.AUTO) {
      if (_nclass == 1) _parms._distribution = Distribution.Family.gaussian;
      if (_nclass >= 2) _parms._distribution = Distribution.Family.multinomial;
    }
    if (expensive) {
      _initialPrediction = isClassifier() ? 0 : getInitialValue();
    }
    if (_parms._sample_rate == 1f && _valid == null)
      error("_sample_rate", "Sample rate is 100% and no validation dataset is specified.  There are no OOB data to compute out-of-bag error estimation!");
    if (hasOffsetCol())
      error("_offset_column", "Offsets are not yet supported for DRF.");
    if (hasOffsetCol() && isClassifier()) {
      error("_offset_column", "Offset is only supported for regression.");
    }
  }

  // A standard DTree with a few more bits.  Support for sampling during
  // training, and replaying the sample later on the identical dataset to
  // e.g. compute OOBEE.
  static class DRFTree extends DTree {
    final int _mtrys;           // Number of columns to choose amongst in splits
    final long _seeds[];        // One seed for each chunk, for sampling
    final transient Random _rand; // RNG for split decisions & sampling
    DRFTree( Frame fr, int ncols, char nbins, char nbins_cats, char nclass, double min_rows, int mtrys, long seed ) {
      super(fr._names, ncols, nbins, nbins_cats, nclass, min_rows, seed);
      _mtrys = mtrys;
      _rand = createRNG(seed);
      _seeds = new long[fr.vecs()[0].nChunks()];
      for( int i=0; i<_seeds.length; i++ )
        _seeds[i] = _rand.nextLong();
    }
    // Return a deterministic chunk-local RNG.  Can be kinda expensive.
    public Random rngForChunk( int cidx ) {
      long seed = _seeds[cidx];
      return createRNG(seed);
    }
  }

  /** Fill work columns:
   *   - classification: set 1 in the corresponding wrk col according to row response
   *   - regression:     copy response into work column (there is only 1 work column)
   */
  private class SetWrkTask extends MRTask<SetWrkTask> {
    @Override public void map( Chunk chks[] ) {
      Chunk cy = chk_resp(chks);
      for( int i=0; i<cy._len; i++ ) {
        if( cy.isNA(i) ) continue;
        if (isClassifier()) {
          int cls = (int)cy.at8(i);
          chk_work(chks,cls).set(i,1L);
        } else {
          float pred = (float) cy.atd(i);
          chk_work(chks,0).set(i,pred);
        }
      }
    }
  }

  // ----------------------
  private class DRFDriver extends Driver {

    // --- Private data handled only on master node
    // Classification or Regression:
    // Tree votes/SSE of individual trees on OOB rows
    public transient TreeMeasuresCollector.TreeMeasures _treeMeasuresOnOOB;
    // Tree votes/SSE per individual features on permutated OOB rows
    public transient TreeMeasuresCollector.TreeMeasures[/*features*/] _treeMeasuresOnSOOB;
    // Variable importance beased on tree split decisions
    private transient float[/*nfeatures*/] _improvPerVar;

    private void initTreeMeasurements() {
      _improvPerVar = new float[_ncols];
      final int ntrees = _parms._ntrees;
      // Preallocate tree votes
      if (_model._output.isClassifier()) {
        _treeMeasuresOnOOB  = new TreeMeasuresCollector.TreeVotes(ntrees);
        _treeMeasuresOnSOOB = new TreeMeasuresCollector.TreeVotes[_ncols];
        for (int i=0; i<_ncols; i++) _treeMeasuresOnSOOB[i] = new TreeMeasuresCollector.TreeVotes(ntrees);
      } else {
        _treeMeasuresOnOOB  = new TreeMeasuresCollector.TreeSSE(ntrees);
        _treeMeasuresOnSOOB = new TreeMeasuresCollector.TreeSSE[_ncols];
        for (int i=0; i<_ncols; i++) _treeMeasuresOnSOOB[i] = new TreeMeasuresCollector.TreeSSE(ntrees);
      }
    }

    @Override protected void buildModel() {
      // Start with class distribution as null-model
      // FIXME: Test/Investigate this
//      if( _nclass >= 2 ) {
//        for( int c=0; c<_nclass; c++ ) {
//          final double init = _model._output._priorClassDist[c];
//          new MRTask() {
//            @Override public void map(Chunk tree) { for( int i=0; i<tree._len; i++ ) tree.set(i, init); }
//          }.doAll(vec_tree(_train,c));
//        }
//      }

      _mtry = (_parms._mtries==-1) ? // classification: mtry=sqrt(_ncols), regression: mtry=_ncols/3
              ( isClassifier() ? Math.max((int)Math.sqrt(_ncols),1) : Math.max(_ncols/3,1))  : _parms._mtries;
      // How many trees was in already in provided checkpointed model
      int ntreesFromCheckpoint = _parms.hasCheckpoint() ?
              ((SharedTreeModel.SharedTreeParameters) _parms._checkpoint.<SharedTreeModel>get()._parms)._ntrees : 0;

      if (!(1 <= _mtry && _mtry <= _ncols)) throw new IllegalArgumentException("Computed mtry should be in interval <1,"+_ncols+"> but it is " + _mtry);
      // Initialize TreeVotes for classification, MSE arrays for regression
      initTreeMeasurements();
      // Append number of trees participating in on-the-fly scoring
      _train.add("OUT_BAG_TREES", _response.makeZero());
      // Prepare working columns
      new SetWrkTask().doAll(_train);
      // If there was a check point recompute tree_<_> and oob columns based on predictions from previous trees
      // but only if OOB validation is requested.
      if (_parms.hasCheckpoint()) {
        Timer t = new Timer();
        // Compute oob votes for each output level
        new OOBScorer(_ncols, _nclass, numSpecialCols(), _parms._sample_rate, _model._output._treeKeys).doAll(_train);
        Log.info("Reconstructing oob stats from checkpointed model took " + t);
      }

      // The RNG used to pick split columns
      Random rand = createRNG(_parms._seed);
      // To be deterministic get random numbers for previous trees and
      // put random generator to the same state
      for (int i = 0; i < ntreesFromCheckpoint; i++) rand.nextLong();

      int tid;

      // Prepare tree statistics
      // Build trees until we hit the limit
      for( tid=0; tid < _ntrees; tid++) { // Building tid-tree
        if (tid!=0 || !_parms.hasCheckpoint()) { // do not make initial scoring if model already exist
          double training_r2 = doScoringAndSaveModel(false, true, _parms._build_tree_one_node);
          if( training_r2 >= _parms._r2_stopping ) {
            doScoringAndSaveModel(true, true, _parms._build_tree_one_node);
            return;             // Stop when approaching round-off error
          }
        }
        // At each iteration build K trees (K = nclass = response column domain size)

        // TODO: parallelize more? build more than k trees at each time, we need to care about temporary data
        // Idea: launch more DRF at once.
        Timer kb_timer = new Timer();
        buildNextKTrees(_train,_mtry,_parms._sample_rate,rand,tid);
        Log.info((tid+1) + ". tree was built " + kb_timer.toString());
        DRF.this.update(1);
        if( !isRunning() ) return; // If canceled during building, do not bulkscore

      }
      doScoringAndSaveModel(true, true, _parms._build_tree_one_node);
    }



    // --------------------------------------------------------------------------
    // Build the next random k-trees representing tid-th tree
    private void buildNextKTrees(Frame fr, int mtrys, float sample_rate, Random rand, int tid) {
      // We're going to build K (nclass) trees - each focused on correcting
      // errors for a single class.
      final DTree[] ktrees = new DTree[_nclass];

      // Initial set of histograms.  All trees; one leaf per tree (the root
      // leaf); all columns
      DHistogram hcs[][][] = new DHistogram[_nclass][1/*just root leaf*/][_ncols];

      // Adjust real bins for the top-levels
      int adj_nbins = Math.max(_parms._nbins_top_level,_parms._nbins);

      // Use for all k-trees the same seed. NOTE: this is only to make a fair
      // view for all k-trees
      final double[] _distribution = _model._output._distribution;
      long rseed = rand.nextLong();
        // Initially setup as-if an empty-split had just happened
      for (int k = 0; k < _nclass; k++) {
        if (_distribution[k] != 0) { // Ignore missing classes
          // The Boolean Optimization
          // This optimization assumes the 2nd tree of a 2-class system is the
          // inverse of the first (and that the same columns were picked)
          if( k==1 && _nclass==2 && _model.binomialOpt()) continue;
          ktrees[k] = new DRFTree(fr, _ncols, (char)_parms._nbins, (char)_parms._nbins_cats, (char)_nclass, _parms._min_rows, mtrys, rseed);
          new DRFUndecidedNode(ktrees[k], -1, DHistogram.initialHist(fr, _ncols, adj_nbins, _parms._nbins_cats, hcs[k][0])); // The "root" node
        }
      }

      // Sample - mark the lines by putting 'OUT_OF_BAG' into nid(<klass>) vector
      Timer t_1 = new Timer();
      Sample ss[] = new Sample[_nclass];
      for( int k=0; k<_nclass; k++)
        if (ktrees[k] != null) ss[k] = new Sample((DRFTree)ktrees[k], sample_rate).dfork(0,new Frame(vec_nids(fr,k),vec_resp(fr)), _parms._build_tree_one_node);
      for( int k=0; k<_nclass; k++)
        if( ss[k] != null ) ss[k].getResult();
      Log.debug("Sampling took: + " + t_1);

      int[] leafs = new int[_nclass]; // Define a "working set" of leaf splits, from leafs[i] to tree._len for each tree i

      // ----
      // One Big Loop till the ktrees are of proper depth.
      // Adds a layer to the trees each pass.
      Timer t_2 = new Timer();
      int depth=0;
      for( ; depth<_parms._max_depth; depth++ ) {
        if( !isRunning() ) return;
        hcs = buildLayer(fr, _parms._nbins, _parms._nbins_cats, ktrees, leafs, hcs, true, _parms._build_tree_one_node);
        // If we did not make any new splits, then the tree is split-to-death
        if( hcs == null ) break;
      }
      Log.debug("Tree build took: " + t_2);

      // Each tree bottomed-out in a DecidedNode; go 1 more level and insert
      // LeafNodes to hold predictions.
      Timer t_3 = new Timer();
      for( int k=0; k<_nclass; k++ ) {
        DTree tree = ktrees[k];
        if( tree == null ) continue;
        int leaf = leafs[k] = tree.len();
        for( int nid=0; nid<leaf; nid++ ) {
          if( tree.node(nid) instanceof DecidedNode ) {
            DecidedNode dn = tree.decided(nid);
            if( dn._split._col == -1 ) { // No decision here, no row should have this NID now
              if( nid==0 ) {               // Handle the trivial non-splitting tree
                LeafNode ln = new DRFLeafNode(tree, -1, 0);
                ln._pred = (float)(isClassifier() ? _model._output._priorClassDist[k] : _initialPrediction);
              }
              continue;
            }
            for( int i=0; i<dn._nids.length; i++ ) {
              int cnid = dn._nids[i];
              if( cnid == -1 || // Bottomed out (predictors or responses known constant)
                  tree.node(cnid) instanceof UndecidedNode || // Or chopped off for depth
                  (tree.node(cnid) instanceof DecidedNode &&  // Or not possible to split
                   ((DecidedNode)tree.node(cnid))._split.col()==-1) ) {
                LeafNode ln = new DRFLeafNode(tree,nid);
                ln._pred = (float)dn.pred(i);  // Set prediction into the leaf
                dn._nids[i] = ln.nid(); // Mark a leaf here
              }
            }
          }
        }
      } // -- k-trees are done
      Log.debug("Nodes propagation: " + t_3);


      // ----
      // Move rows into the final leaf rows
      Timer t_4 = new Timer();
      CollectPreds cp = new CollectPreds(ktrees,leafs,_model.defaultThreshold()).doAll(fr,_parms._build_tree_one_node);

      if (isClassifier())   asVotes(_treeMeasuresOnOOB).append(cp.rightVotes, cp.allRows); // Track right votes over OOB rows for this tree
      else /* regression */ asSSE  (_treeMeasuresOnOOB).append(cp.sse, cp.allRows);
      Log.debug("CollectPreds done: " + t_4);

      // Grow the model by K-trees
      _model._output.addKTrees(ktrees);
    }


    // Collect and write predictions into leafs.
    private class CollectPreds extends MRTask<CollectPreds> {
      /* @IN  */ final DTree _trees[]; // Read-only, shared (except at the histograms in the Nodes)
      /* @IN */  double _threshold;      // Sum of squares for this tree only
      /* @OUT */ long rightVotes; // number of right votes over OOB rows (performed by this tree) represented by DTree[] _trees
      /* @OUT */ long allRows;    // number of all OOB rows (sampled by this tree)
      /* @OUT */ float sse;      // Sum of squares for this tree only
      CollectPreds(DTree trees[], int leafs[], double threshold) { _trees=trees; _threshold = threshold; }
      final boolean importance = true;
      @Override public void map( Chunk[] chks ) {
        final Chunk    y       = importance ? chk_resp(chks) : null; // Response
        final double[] rpred   = importance ? new double[1+_nclass] : null; // Row prediction
        final double[] rowdata = importance ? new double[_ncols] : null; // Pre-allocated row data
        final Chunk   oobt  = chk_oobt(chks); // Out-of-bag rows counter over all trees
        // Iterate over all rows
        for( int row=0; row<oobt._len; row++ ) {
          final boolean wasOOBRow = ScoreBuildHistogram.isOOBRow((int)chk_nids(chks,0).at8(row));

          // For all tree (i.e., k-classes)
          for( int k=0; k<_nclass; k++ ) {
            final DTree tree = _trees[k];
            if( tree == null ) continue; // Empty class is ignored
            final Chunk nids = chk_nids(chks, k); // Node-ids  for this tree/class
            int nid = (int)nids.at8(row);         // Get Node to decide from
            // Update only out-of-bag rows
            // This is out-of-bag row - but we would like to track on-the-fly prediction for the row
            if( wasOOBRow) {
              final Chunk ct   = chk_tree(chks,k); // k-tree working column holding votes for given row
              nid = ScoreBuildHistogram.oob2Nid(nid);
              if( tree.node(nid) instanceof UndecidedNode ) // If we bottomed out the tree
                nid = tree.node(nid).pid();                 // Then take parent's decision
              int leafnid;
              if( tree.root() instanceof LeafNode ) {
                leafnid = 0;
              } else {
                DecidedNode dn = tree.decided(nid);           // Must have a decision point
                if (dn._split.col() == -1)     // Unable to decide?
                  dn = tree.decided(tree.node(nid).pid());    // Then take parent's decision
                leafnid = dn.ns(chks, row); // Decide down to a leafnode
              }
              // Setup Tree(i) - on the fly prediction of i-tree for row-th row
              //   - for classification: cumulative number of votes for this row
              //   - for regression: cumulative sum of prediction of each tree - has to be normalized by number of trees
              double prediction = ((LeafNode) tree.node(leafnid)).pred(); // Prediction for this k-class and this row
              if (importance) rpred[1 + k] = (float) prediction; // for both regression and classification
              ct.set(row, (float) (ct.atd(row) + prediction));
            }
            // reset help column for this row and this k-class
            nids.set(row, 0);
          } /* end of k-trees iteration */
          // For this tree this row is out-of-bag - i.e., a tree voted for this row
          if (wasOOBRow) oobt.set(row, oobt.atd(row) + 1); // track number of trees
          if (importance) {
            if (wasOOBRow && !y.isNA(row)) {
              if (isClassifier()) {
                int treePred = getPrediction(rpred, _model._output._priorClassDist, data_row(chks, row, rowdata), _threshold);
                int actuPred = (int) y.at8(row);
                if (treePred==actuPred) rightVotes++; // No miss !
              } else { // regression
                double treePred = rpred[1];
                double actuPred = y.atd(row);
                sse += (actuPred-treePred)*(actuPred-treePred);
              }
              allRows++;
            }
          }
        }
      }
      @Override public void reduce(CollectPreds mrt) {
        rightVotes += mrt.rightVotes;
        allRows    += mrt.allRows;
        sse        += mrt.sse;
      }
    }



    @Override protected DRFModel makeModel( Key modelKey, DRFModel.DRFParameters parms, double mse_train, double mse_valid ) {
      return new DRFModel(modelKey,parms,new DRFModel.DRFOutput(DRF.this,mse_train,mse_valid));
    }

  }

  @Override protected DecidedNode makeDecided( UndecidedNode udn, DHistogram hs[] ) {
    return new DRFDecidedNode(udn,hs);
  }
  
  // ---
  // DRF DTree decision node: same as the normal DecidedNode, but
  // specifies a decision algorithm given complete histograms on all
  // columns.  DRF algo: find the lowest error amongst *all* columns.
  static class DRFDecidedNode extends DecidedNode {
    DRFDecidedNode( UndecidedNode n, DHistogram[] hs ) { super(n,hs); }
    @Override public UndecidedNode makeUndecidedNode(DHistogram[] hs ) {
      return new DRFUndecidedNode(_tree,_nid,hs);
    }
  
    // Find the column with the best split (lowest score).  Unlike RF, DRF
    // scores on all columns and selects splits on all columns.
    @Override public DTree.Split bestCol( UndecidedNode u, DHistogram[] hs ) {
      DTree.Split best = new DTree.Split(-1,-1,null,(byte)0,Double.MAX_VALUE,Double.MAX_VALUE,Double.MAX_VALUE,0L,0L,0,0);
      if( hs == null ) return best;
      for( int i=0; i<u._scoreCols.length; i++ ) {
        int col = u._scoreCols[i];
        DTree.Split s = hs[col].scoreMSE(col, _tree._min_rows);
        if( s == null ) continue;
        if( s.se() < best.se() ) best = s;
        if( s.se() <= 0 ) break; // No point in looking further!
      }
      return best;
    }
  }
  
  // ---
  // DRF DTree undecided node: same as the normal UndecidedNode, but specifies
  // a list of columns to score on now, and then decide over later.
  static class DRFUndecidedNode extends UndecidedNode {
    DRFUndecidedNode( DTree tree, int pid, DHistogram hs[] ) { super(tree,pid,hs); }
    // Randomly select mtry columns to 'score' in following pass over the data.
    @Override public int[] scoreCols( DHistogram[] hs ) {
      DRFTree tree = (DRFTree)_tree;
      int[] cols = new int[hs.length];
      int len=0;
      // Gather all active columns to choose from.
      for( int i=0; i<hs.length; i++ ) {
        if( hs[i]==null ) continue; // Ignore not-tracked cols
        assert hs[i]._min < hs[i]._maxEx && hs[i].nbins() > 1 : "broken histo range "+hs[i];
        cols[len++] = i;        // Gather active column
      }
      int choices = len;        // Number of columns I can choose from
      assert choices > 0;

      // Draw up to mtry columns at random without replacement.
      for( int i=0; i<tree._mtrys; i++ ) {
        if( len == 0 ) break;   // Out of choices!
        int idx2 = tree._rand.nextInt(len);
        int col = cols[idx2];     // The chosen column
        cols[idx2] = cols[--len]; // Compress out of array; do not choose again
        cols[len] = col;          // Swap chosen in just after 'len'
      }
      assert choices - len > 0;
      return Arrays.copyOfRange(cols, len, choices);
    }
  }
  
  // ---
  static class DRFLeafNode extends LeafNode {
    DRFLeafNode( DTree tree, int pid ) { super(tree,pid); }
    DRFLeafNode( DTree tree, int pid, int nid ) { super(tree, pid, nid); }
    // Insert just the predictions: a single byte/short if we are predicting a
    // single class, or else the full distribution.
    @Override protected AutoBuffer compress(AutoBuffer ab) { assert !Double.isNaN(_pred); return ab.put4f(_pred); }
    @Override protected int size() { return 4; }
  }

  // Deterministic sampling
  static class Sample extends MRTask<Sample> {
    final DRFTree _tree;
    final float _rate;
    Sample( DRFTree tree, float rate ) { _tree = tree; _rate = rate; }
    @Override public void map( Chunk nids, Chunk ys ) {
      Random rand = _tree.rngForChunk(nids.cidx());
      for( int row=0; row<nids._len; row++ )
        if( rand.nextFloat() >= _rate || Double.isNaN(ys.atd(row)) ) {
          nids.set(row, ScoreBuildHistogram.OUT_OF_BAG);     // Flag row as being ignored by sampling
        }
    }
  }

  // Read the 'tree' columns, do model-specific math and put the results in the
  // fs[] array, and return the sum.  Dividing any fs[] element by the sum
  // turns the results into a probability distribution.
  @Override protected double score1( Chunk chks[], double weight, double offset, double fs[/*nclass*/], int row ) {
    double sum = 0;
    if (_nclass > 2 || (_nclass == 2 && !_model.binomialOpt())) {
      for (int k = 0; k < _nclass; k++)
        sum += (fs[k+1] = chk_tree(chks, k).atd(row) / chk_oobt(chks).atd(row));
    }
    else if (_nclass==2 && _model.binomialOpt()) {
      fs[1] = chk_tree(chks, 0).atd(row) / chk_oobt(chks).atd(row);
      assert(fs[1] >= 0 && fs[1] <= 1);
      fs[2] = 1. - fs[1];
    }
    else { //regression
      // average per trees voted for this row (only trees which have row in "out-of-bag"
      sum += (fs[0] = chk_tree(chks, 0).atd(row) / chk_oobt(chks).atd(row) );
      fs[1] = 0;
    }
    return sum;
  }


}
