import sys
sys.path.insert(1, "../../../")
import h2o, tests

def anyfactor():
    
    

    iris = h2o.import_file(path=h2o.locate("smalldata/iris/iris.csv"))

    # frame (positive example)
    assert iris.anyfactor(), "Expected true, but got false. Column 5 is a factor."

    # frame (negative example)
    assert not iris[:,:4].anyfactor(), "Expected false, but got true. Columns 1-4 are numeric."

    # vec (positive example)
    assert iris[4].anyfactor(), "Expected true, but got false. Column 5 is a factor."

    # vec (negative example)
    assert not iris[0].anyfactor(), "Expected false, but got true. Columns 1 is numeric."

if __name__ == "__main__":
    tests.run_test(sys.argv, anyfactor)
