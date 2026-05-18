name := "parcel-tracker"
version := "0.1.0-SNAPSHOT"
scalaVersion := "2.13.12"

lazy val root = (project in file(".")).enablePlugins(PlayScala)

libraryDependencies ++= Seq(
  guice,      // Play's dependency injection framework (auto-wires your controllers/repos)
  jdbc,       // Play's JDBC support — gives you the Database object Anorm needs
  evolutions, // Play's built-in DB migration runner
  "org.playframework.anorm" %% "anorm"      % "2.6.7", // thin SQL library: write raw SQL, get typed results
  "org.postgresql"           %  "postgresql" % "42.7.3", // JDBC driver

  // Test dependencies (% Test means these jars are only on the test classpath)
  "org.scalatestplus.play" %% "scalatestplus-play" % "7.0.1"    % Test, // PlaySpec, route(), FakeRequest, GuiceOneAppPerSuite
  "org.scalatestplus"      %% "mockito-5-12"       % "3.2.19.0" % Test, // mock[T], when().thenReturn()
  "com.h2database"          %  "h2"                % "2.2.224"  % Test, // in-memory DB so tests don't need Docker running

  // Phase 2: Pub/Sub event publishing
  "com.google.cloud"        %  "google-cloud-pubsub" % "1.128.1", // Google Cloud Pub/Sub Java client

  // Phase 3: Cassandra event log
  // -shaded bundles Netty/Jackson under a relocated package, avoiding version conflicts with Play's own Netty
  "com.datastax.oss"        %  "java-driver-core-shaded" % "4.17.0"
)
