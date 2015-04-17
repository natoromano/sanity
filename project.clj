(defproject comportexviz "0.0.8-SNAPSHOT"
  :description "Web visualisation of HTM algorithm as implemented in comportex"
  :url "https://github.com/nupic-community/comportexviz"

  :dependencies [[org.clojure/clojure "1.7.0-beta1"]
                 [org.clojure/clojurescript "0.0-3196"]
                 [org.clojure/core.async "0.1.346.0-17112a-alpha"]
                 [org.nfrac/comportex "0.0.8-SNAPSHOT"]
                 [rm-hull/monet "0.2.1"]
                 [reagent "0.5.0"]
                 [reagent-forms "0.5.0"]

                 [com.keminglabs/c2 "0.2.4-SNAPSHOT"]]

  :plugins [[lein-cljsbuild "1.0.5"]
            [com.cemerick/austin "0.1.6"]]
  :repl-options {:nrepl-middleware [cemerick.piggieback/wrap-cljs-repl]}

  :source-paths ["src"]

  :clean-targets ["public/demos/target"
                  "public/cortical_io/target"
                  "public/local_inhibition/target"]

  :cljsbuild {:builds [{:id "demos"
                        :source-paths ["src" "examples/demos"]
                        :compiler {:optimizations :whitespace
                                   :output-dir "public/demos/target"
                                   :source-map "public/demos/comportexviz.js.map"
                                   :output-to "public/demos/comportexviz.js"}}
                       {:id "cortical-io"
                        :source-paths ["src" "examples/cortical_io"]
                        :compiler {:optimizations :whitespace
                                   :output-dir "public/cortical_io/target"
                                   :source-map "public/cortical_io/comportexviz_cio.js.map"
                                   :output-to "public/cortical_io/comportexviz_cio.js"}}
                       {:id "inh"
                        :source-paths ["src" "examples/local_inhibition"]
                        :compiler {:optimizations :whitespace
                                   :output-dir "public/local_inhibition/target"
                                   :source-map "public/local_inhibition/comportexviz_inh.js.map"
                                   :output-to "public/local_inhibition/comportexviz_inh.js"}}
                       ]})
