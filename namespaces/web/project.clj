(defproject org.immutant/immutant-web "1.0.3-SNAPSHOT"
  :description "The Immutant web module."
  :plugins [[lein-modules "0.1.0-SNAPSHOT"]
            [org.immutant/build-plugin "0.1.0-SNAPSHOT"]]
  :modules {:parent "../project.clj"}
  :dependencies [[javax.servlet/javax.servlet-api "3.0.1"]
                 [org.immutant/immutant-common _]
                 [org.tcrawley/dynapath "0.2.3"]
                 [ring/ring-devel _]
                 [ring/ring-servlet _]
                 [org.immutant/immutant-web-module :immutant :scope "provided"]]
  :src-jar "../../modules/web/target/immutant-web-module-${version}.jar")