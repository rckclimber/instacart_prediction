package main
import etl.objDataProcessing
import shapeless.Data
import org.sparkproject.dmg.pmml.True
import java.util.zip.DataFormatException
import org.apache.log4j.Logger
import org.apache.spark.rdd.RDD
import org.apache.spark.sql.{DataFrame, SparkSession}
import org.apache.spark.sql.functions._
import org.apache.spark.ml.feature.VectorAssembler
import org.apache.spark.ml.fpm.FPGrowth
import org.apache.spark.ml.feature.Normalizer
import org.apache.spark.mllib.linalg.{Vector,Vectors}
import org.apache.spark.mllib.linalg.distributed.{CoordinateMatrix, MatrixEntry}
import org.apache.spark.mllib.linalg.distributed.RowMatrix
import org.apache.spark.mllib.recommendation.ALS
import org.apache.spark.mllib.recommendation.MatrixFactorizationModel
import org.apache.spark.mllib.recommendation.Rating




object objItemMatrix {
    println("In ItemMatrix")
    val spark = SparkSession
        .builder()
        .appName("Instacart Prediction Project")
        .config("spark.master", "local[*]")
        .getOrCreate()
    import spark.implicits._
    spark.sparkContext.setLogLevel("ERROR") //To avoid warnings
 

    def generateItemItemMatrix(inputDf: DataFrame): DataFrame = {
        println("\nChecking Processed DataSet: ")
        inputDf.show(5)
        println("\nSelecting only required columns: user_id, product_id, order_id...")
        val subDf = inputDf.select("user_id","product_id", "order_id")
        
        subDf.show(10)
        //println("\nDataset RowCount: "+subDf.count)
        //println("\nDistinct RowCount: "+subDf.distinct.count)

        println  ("\nGenerating ItemItem matrix")
        val columnNames = subDf.select("product_id").distinct.map(
                        row => row(0).asInstanceOf[Int]).collect().toSeq
        println("\nColmnNames Length: "+columnNames.length)
         //setting max values for Pivot, since we have total 50k products
        spark.conf.set("spark.sql.pivotMaxValues", columnNames.length)

        val itemMatrixDf = subDf.drop("user_id")
                .withColumnRenamed("product_id","product_id_left")
                .as("df1")
                .join(subDf.as("df2"),$"df1.order_id" === $"df2.order_id")
                .withColumn("ones",lit(1))
                .withColumnRenamed("product_id","product_id_right")
                .drop("order_id","user_id")
                .groupBy("product_id_left")
                .pivot("product_id_right",columnNames)
                .count()
                .na.fill(0)

        println("\nMatrix Generated")
        itemMatrixDf
    }

    def generateNormalisedMatrix(inputDf: DataFrame): DataFrame ={

        println("\nGenerting Normalised matrix, \n\nStep1: Assembling feature columns as Vectors")
        val columnNames = inputDf.columns
        val assembler = new VectorAssembler()
            .setInputCols(columnNames)
            .setOutputCol("features")
        
        val output = assembler.transform(inputDf)
        println("\nAll columns combined to a vector column named 'features'\n")
        println("\nMatrix RowSize: "+output.count())
        
        val normalizer = new Normalizer()
                .setInputCol("features")
                .setOutputCol("normFeatures")
                .setP(1.0)
        
        val l1NormData = normalizer.transform(output)
        println("\nNormalized using L^1 norm")
        l1NormData
    }
    
    def generateItemItem(inputDf: DataFrame): DataFrame = {

        val filteredDf = inputDf.sample(true, 0.1)

        val dfOriginal = filteredDf.withColumnRenamed(
            "user_id", "user_id_1").withColumnRenamed(
            "product_id", "product_id_1").withColumnRenamed(
            "order_id", "order_id_1")

        val dfMirror = filteredDf.withColumnRenamed(
            "user_id", "user_id_2").withColumnRenamed(
            "product_id", "product_id_2").withColumnRenamed(
            "order_id", "order_id_2")

        val dfBasketJoin = dfOriginal.join(
            dfMirror, 
            dfOriginal("user_id_1") === dfMirror("user_id_2") && dfOriginal("order_id_1") === dfMirror("order_id_2"), 
            "left_outer").withColumn(
            "ones", lit(1))

        dfBasketJoin

    }

    def userItemMatrixAls(filteredDF: DataFrame) = {
        /*
            Using default Alternate Least Squares method provided in Spark
        */
        val userItemDf = filteredDF.groupBy("user_id","product_id").count()
        userItemDf.printSchema()
        userItemDf.show(25)
        val purchases = userItemDf.rdd.map( 
            row => Rating(row.getAs[Int](0), row.getAs[Int](1), row.getLong(2).toDouble))
        val rank = 10
        val numIterations = 10
        val model = ALS.train(purchases, rank, numIterations, 0.01)
        
        println("\nModel Ranks:\n"+model.rank)
        // Evaluate the model on purchase counts
        val usersProducts = purchases.map { 
            case Rating(user, product, purchaseCount) => (user, product)
        }

        println("\nUser Products:\n"+usersProducts.top(5))

        val predictions = model.predict(usersProducts).map { 
            case Rating(user, product, purchaseCount) =>
            ((user, product), purchaseCount)
        }
        println("\nPredictions:\n"+predictions.top(5))

        val ratesAndPreds = purchases.map { 
            case Rating(user, product, purchaseCount) =>((user, product), purchaseCount)
        }.join(predictions)
        
        println("\nRates &  Predictions:\n"+ratesAndPreds.top(5))

        val MSE = ratesAndPreds.map { 
            case ((user, product), (r1, r2)) => 
            val err = (r1 - r2) 
            err * err
        }.mean()
       
        println(s"\nMean Squared Error = $MSE\n")
        //Mean Squared Error = 0.6048095711284814

    }

    def generateUserItemMatrix(filteredDf: DataFrame) = {
        /*
          Generate a userItemMatrix from dataframe and calculate similarties between each rows (ie users) using cosine similarity
         
        */
        println("\n**** Attempt to generate userItem Matrix & calculating cosine similarities **** \n")
       
        val userItemDf = filteredDf.groupBy("user_id").pivot("product_id").count()
        
        
        println("\nUserItemMatrix rowLength: "+ userItemDf.count()+" | UserItemMatrix columnLength: " + userItemDf.columns.size)
        val userItemDf2 = userItemDf.na.fill(0)
        //DataProcessing.writeToCSV(userItemDf,"data/userItemDf.csv")
       
        val columnNames = userItemDf.columns.drop(1)//dropping user_id as column
        //println("\ncolumn length: "+columnNames.length+" Column Names:\n"+columnNames.toSeq)
        println("Assembling Vectors")
        val assembler = new VectorAssembler()
            .setInputCols(columnNames)
            .setOutputCol("productsPurchased")
        
        val output = assembler.transform(userItemDf2)
        println("\nAll columns combined to a vector column named 'productsPurchased'\n")
        output.select("user_id","productsPurchased").show(5)
        println("\nMatrix RowSize: "+output.select("user_id","productsPurchased").count())
        
        //val outputRdd = output.select("user_id","productsPurchased").rdd.map{
            //row => Vectors.dense(row.getAs[Seq[Double]](1).toArray)
            //row.getAs[Vector](1)
        val outputRdd = output.select("user_id","productsPurchased").rdd.map{
            row => Vectors.dense(row.getAs[Seq[Double]](1).toArray)
            }
        print("\n RDD size count: "+outputRdd.count())
        val prodPurchasePerUserRowMatrix = new RowMatrix(outputRdd)
            //http://spark.apache.org/docs/latest/ml-migration-guides.html
        // Compute cosine similarity between columns 

        val simsCols = prodPurchasePerUserRowMatrix.columnSimilarities()
        println("\nNo. of Cols: "+simsCols.numCols()+ "\n No. of Rows: "+ simsCols.numRows())
        
    }
}