import sys
sys.path.insert(1, "../../../")
import h2o, tests

def iris_get_model():
    
    

    iris = h2o.import_file(path=h2o.locate("smalldata/iris/iris.csv"))

    model = h2o.random_forest(y=iris[4], x=iris[0:4], ntrees=50)
    model.show()

    model = h2o.get_model(model._id)
    model.show()

if __name__ == "__main__":
  tests.run_test(sys.argv, iris_get_model)
