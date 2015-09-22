package water.fvec;

import org.junit.Assert;
import org.junit.BeforeClass;
import org.junit.Test;
import water.Futures;
import water.TestUtil;

import static org.junit.Assert.assertTrue;
import static water.fvec.Vec.makeCon;
import static water.fvec.Vec.makeSeq;

/** This test tests stability of Vec API. */
public class VecTest extends TestUtil {
  @BeforeClass public static void setup() { stall_till_cloudsize(1); }

  /** Test toEnum call to return correct domain. */
  @Test public void testToEnum() {
    testToEnumDomainMatch(vec(0,1,0,1), ar("0", "1") );
    testToEnumDomainMatch(vec(1,2,3,4,5,6,7), ar("1", "2", "3", "4", "5", "6", "7") );
    testToEnumDomainMatch(vec(0, 1, 2, 99, 4, 5, 6), ar("0", "1", "2", "4", "5", "6", "99"));
  }

  private void testToEnumDomainMatch(Vec f, String[] expectedDomain) {
    Vec ef = null;
    try {
      ef = f.toEnum();
      String[] actualDomain = ef.domain();
      Assert.assertArrayEquals("toEnum call returns wrong domain!", expectedDomain, actualDomain);
    } finally {
      if( f !=null ) f .remove();
      if( ef!=null ) ef.remove();
    }
  }

  @Test public void testUniques() {
    testUniques(vec(1,1,1,1,1), Vec.Uniques.ONE);
    testUniques(vec(new String[]{"T","F"},1,0,0,0,0,1,1,1,0,0,0), Vec.Uniques.TWO);
    testUniques(vec(new String[]{"T","F"},1,-3,-3,-3,1,1,1,1,-3,-3), Vec.Uniques.TWO);
    testUniques(vec(new String[]{"T","F"},1,-1,-1,-1,1,1,1,1,-1,-1), Vec.Uniques.TWO);
    testUniques(vec(Math.PI,Math.PI,Math.PI), Vec.Uniques.ONE);
    testUniques(vec(0,0,0,0,0,1,1,0,1), Vec.Uniques.TWO);
    testUniques(vec(-Math.PI,1,4), Vec.Uniques.THREE);
    testUniques(vec(0,1,1,0,1), Vec.Uniques.TWO);
    testUniques(vec(-Math.PI,1.34,4.2,5.3,5.3,5.3,-Math.PI), Vec.Uniques.FOUR);
    testUniques(vec(2,1,1,0,1), Vec.Uniques.THREE);
    testUniques(vec(2,0,1), Vec.Uniques.THREE);
    testUniques(vec(Double.MAX_VALUE,1,1,0,1), Vec.Uniques.THREE);
    testUniques(vec(-Double.MAX_VALUE,Double.MAX_VALUE,1,1,1), Vec.Uniques.THREE);
    testUniques(vec(-Double.MAX_VALUE,Double.MAX_VALUE,1,2,3), Vec.Uniques.FIVE);
    testUniques(vec(5,-Double.MAX_VALUE,Double.MAX_VALUE,1,2,3), Vec.Uniques.MANY);
    testUniques(vec(5,7,8,2,2,2,2,3), Vec.Uniques.FIVE);
    testUniques(vec(5,7,8,1,2,3,7,8,7), Vec.Uniques.MANY);
    testUniques(vec(-1,1,1,1,1,1,1,1,-1,-1), Vec.Uniques.TWO);
    testUniques(vec(12,12,12,12,12,0,0), Vec.Uniques.TWO);
    testUniques(vec(0,12,0,0,0,0,0,0,0), Vec.Uniques.TWO);
    testUniques(vec(1,3,2,5,5,2,3,1,1,1,3,5,5,5,3,2,1), Vec.Uniques.FOUR);
    testUniques(vec(Double.NaN,0,1), Vec.Uniques.TWO); //NA is not counted
    testUniques(vec(Double.NaN,0,1,1,1,1,1,0,0,0,0), Vec.Uniques.TWO); //NA is not counted
    testUniques(vec(new String[]{"a","b","c","d","e"}, 1,3,2,4,5,2,3,1,1,1,3,5,5,5,3,2,1), Vec.Uniques.FIVE);
  }
  private void testUniques(Vec f, Vec.Uniques expectedUniques) {
    try {
      Assert.assertEquals("Wrong uniques!", expectedUniques, f.numUniques());
    } finally {
      if( f !=null ) f .remove();
    }
  }

  @Test public void testMakeConSeq() {
    Vec v;

    v = makeCon(0xCAFE,2*FileVec.DFLT_CHUNK_SIZE,false);
    assertTrue(v.at(234) == 0xCAFE);
    assertTrue(v._espc.length == 3);
    assertTrue(
            v._espc[0] == 0              &&
                    v._espc[1] == FileVec.DFLT_CHUNK_SIZE
    );
    v.remove(new Futures()).blockForPending();

    v = makeCon(0xCAFE,3*FileVec.DFLT_CHUNK_SIZE,false);
    assertTrue(v.at(234) == 0xCAFE);
    assertTrue(v.at(3*FileVec.DFLT_CHUNK_SIZE-1) == 0xCAFE);
    assertTrue(v._espc.length == 4);
    assertTrue(
            v._espc[0] == 0              &&
                    v._espc[1] == FileVec.DFLT_CHUNK_SIZE   &&
                    v._espc[2] == FileVec.DFLT_CHUNK_SIZE*2
    );
    v.remove(new Futures()).blockForPending();

    v = makeCon(0xCAFE,3*FileVec.DFLT_CHUNK_SIZE+1,false);
    assertTrue(v.at(234) == 0xCAFE);
    assertTrue(v.at(3*FileVec.DFLT_CHUNK_SIZE) == 0xCAFE);
    assertTrue(v._espc.length == 4);
    assertTrue(
            v._espc[0] == 0              &&
                    v._espc[1] == FileVec.DFLT_CHUNK_SIZE   &&
                    v._espc[2] == FileVec.DFLT_CHUNK_SIZE*2 &&
                    v._espc[3] == FileVec.DFLT_CHUNK_SIZE*3+1
    );
    v.remove(new Futures()).blockForPending();

    v = makeCon(0xCAFE,4*FileVec.DFLT_CHUNK_SIZE,false);
    assertTrue(v.at(234) == 0xCAFE);
    assertTrue(v.at(4*FileVec.DFLT_CHUNK_SIZE-1) == 0xCAFE);
    assertTrue(v._espc.length == 5);
    assertTrue(
            v._espc[0] == 0              &&
                    v._espc[1] == FileVec.DFLT_CHUNK_SIZE   &&
                    v._espc[2] == FileVec.DFLT_CHUNK_SIZE*2 &&
                    v._espc[3] == FileVec.DFLT_CHUNK_SIZE*3
    );
    v.remove(new Futures()).blockForPending();
  }

  @Test public void testMakeSeq() {
    Vec v = makeSeq(3*FileVec.DFLT_CHUNK_SIZE, false);
    assertTrue(v.at(0) == 1);
    assertTrue(v.at(234) == 235);
    assertTrue(v.at(2*FileVec.DFLT_CHUNK_SIZE) == 2*FileVec.DFLT_CHUNK_SIZE+1);
    assertTrue(v._espc.length == 4);
    assertTrue(
            v._espc[0] == 0 &&
                    v._espc[1] == FileVec.DFLT_CHUNK_SIZE &&
                    v._espc[2] == FileVec.DFLT_CHUNK_SIZE * 2
    );
    v.remove(new Futures()).blockForPending();
  }
}
