name := "parcel-tracker"
version := "0.1.0-SNAPSHOT"
scalaVersion := "2.13.12"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

libraryDependencies ++= Seq(
  guice,      // Play's dependency injection framework (auto-wires your controllers/repos)
  jdbc,       // Play's JDBC support — gives you the Database object Anorm needs
  evolutions, // Play's built-in DB migration runner
  "org.playframework.anorm" %% "anorm" % "2.6.7", // thin SQL library: write raw SQL, get typed results
  "org.postgresql"           %  "postgresql" % "42.7.3" // JDBC driver
)
