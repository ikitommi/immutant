;; Copyright 2014-2017 Red Hat, Inc, and individual contributors.
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;; http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(defproject ikitommi/immutant-transactions "3.0.0-alpha3"
  :description "Provides support for distributed (XA) transactions."
  :plugins [[lein-modules "0.3.11"]]

  :dependencies [[ikitommi/immutant-core _]
                 [org.projectodd.wunderboss/wunderboss-transactions _]]

  :jvm-opts ["-Dhornetq.data.dir=target/hornetq-data"]

  :profiles {:dev
             {:dependencies [[ikitommi/immutant-messaging _ :exclusions [org.hornetq/hornetq-journal org.hornetq/hornetq-commons]]
                             [ikitommi/immutant-caching _ :exclusions [org.jboss.spec.javax.transaction/jboss-transaction-api_1.1_spec]]
                             [org.clojure/java.jdbc _]
                             [com.h2database/h2 _]]}})
