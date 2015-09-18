setwd(normalizePath(dirname(R.utils::commandArgs(asValues=TRUE)$"f")))
source('../h2o-runit.R')

test.headers <- function() {
  
  # Test to make sure headers are replaced correctly
  headers <- h2o.importFile(locate("smalldata/airlines/allyears2k_headers_only.csv"), destination_frame = "airlines_headers")
  hex <- h2o.importFile(locate("smalldata/airlines/allyears2k.zip"), col.names=headers, destination_frame = "airlines")
  print(names(headers))
  print(names(hex))
  checkIdentical(names(headers), names(hex))

  # Test to make sure files that go through parseRaw gets proper headers
  iris.raw <- h2o.importFile( path = locate("smalldata/iris/iris.csv"), parse = F)
  iris.hex <- h2o.parseRaw( data = iris.raw, destination_frame = "iris.hex",
                            col.types = c("numeric", "numeric", "numeric", "numeric", "enum")) 
  print(names(iris.hex))
  print(paste0("C", 1:5))
  checkIdentical(names(iris.hex), paste0("C", 1:5))
  
  testEnd()
}

doTest("Import a dataset with a header H2OParsedData Object", test.headers)
