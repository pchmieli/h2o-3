


test.pub_171_colname_assign_with_square_brackets <- function() {
  air <- h2o.importFile(normalizePath(locate("smalldata/airlines/allyears2k_headers.zip")), "air")
  print(colnames(air))
  parsed_names <- colnames(air)
  colnames(air)[ncol(air)] <- 'earl'

  print(air)

  print(colnames(air))

  df <- air[,ncol(air)]
  
  expect_that(names(df), equals("earl"))
  
  
}

doTest("PUB-171: Perform colname assign wihth [] and <-", test.pub_171_colname_assign_with_square_brackets)

