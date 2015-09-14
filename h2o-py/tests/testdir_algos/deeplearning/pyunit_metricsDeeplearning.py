import sys
sys.path.insert(1,"../../../")
import h2o, tests


def deep_learning_metrics_test():
                   # connect to existing cluster

    df = h2o.import_file(path=h2o.locate("smalldata/logreg/prostate.csv"))

    df.drop("ID")                              # remove ID
    df['CAPSULE'] = df['CAPSULE'].asfactor()   # make CAPSULE categorical
    vol = df['VOL']
    vol[vol == 0] = float("nan")               # 0 VOL means 'missing'

    r = vol.runif()                            # random train/test split
    train = df[r < 0.8]
    test  = df[r >= 0.8]

    # See that the data is ready
    train.describe()
    train.head()
    train.tail()
    test.describe()
    test.head()
    test.tail()

    # Run DeepLearning
    print "Train a Deeplearning model: "
    dl = h2o.deeplearning(x           = train[1:],
                          y           = train['CAPSULE'],
                          epochs = 100,
                          hidden = [10, 10, 10],
                          loss   = 'CrossEntropy')
    print "Binomial Model Metrics: "
    print
    dl.show()
    dl.model_performance(test).show()


if __name__ == "__main__":
  tests.run_test(sys.argv, deep_learning_metrics_test)
