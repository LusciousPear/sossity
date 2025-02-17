(ns sossity.core
  (:require
   [loom.graph :refer :all]
   [sossity.config-schema :as cs]
   [loom.alg :refer :all]
   [loom.io :refer :all]
   [loom.attr :refer :all]
   [cheshire.core :refer :all]
   [sossity.simulator :as sim]
   [sossity.util :as u]
   [flatland.useful.experimental :refer :all]
   [clojure.tools.cli :as cl :refer :all]
   [traversy.lens :as t :refer :all :exclude [view update combine]]
   [clj-time.core :as ti]
   [clojure.core.async :as a
    :refer [>! <! >!! <!! go chan buffer close! thread alts! alts!! timeout pub sub unsub unsub-all go-loop put!]]
   [schema.core :as s])
  (:gen-class))

(def VALID-CHARS
  (map char (concat (range 48 58) ; 0-9
                    (range 65 91) ; A-Z
                    (range 97 123)))) ; a-z

(defn random-char []
  (rand-nth VALID-CHARS))

(defn random-str [length]
  (apply str (take length (repeatedly random-char))))

(defn sub-suffix [] (str "_sub-" (random-str 6)))

(def app-prefix "googleappengine_app")
(def df-prefix "googlecli_dataflow")
(def pt-prefix "google_pubsub_topic")
(def replication-controller-name-regex #"([a-z0-9]([-a-z0-9]*[a-z0-9])?(\\\\.[a-z0-9]([-a-z0-9]*[a-z0-9])?)*)")
(def angledream-class "com.acacia.angleddream.Main")
(def sink-retries 3)
(def sink-buffer-size 750)
(def sink-container "${google_container_cluster.hx_fstack_cluster.name}")
(def default-bucket-location "EU")
(def default-force-bucket-destroy false)

(def external-port 8080)
(def gstoragebucket "build-artifacts-public-eu")
(def default-min-idle 1)
(def default-max-idle 1)
(def default-min-pending-latency "3s")
(def default-max-pending-latency "6s")

(defn source-topic-name [topic] topic)

#_(defn new-topic-name [in out] (str in "-to-" out))

(defn new-topic-name [g in]
  "Create the naming scheme for a new streaming topic"
  (if (= (attr g in :exec) :sink)
    (str in "-error-out")
    (str in "-out")))

(defn error-topic-name [g in]
  "Crate the naming scheme for an error topic"
  (str in "-error-out"))

(defn item-metadata [node a-graph]
  "Annotate a node in a-graph with the relevant metadata"
  (cond-let
   [item (get (:pipelines a-graph) node)] (assoc item :exec :pipeline)
   [item (get (:sources a-graph) node)] (assoc item :exec :source)
   [item (get (:containers a-graph) node)] (assoc item :exec :container)
   [item (get (:sinks a-graph) node)] (if (= (:type item) "bq")
                                        (assoc item :exec :pipeline)
                                        (assoc item :exec :sink))))

(defn config-md [a-graph]
  "Executable metadata -- see documentation"
  {:config-file a-graph
   :project (get-in a-graph [:provider :project])
   :region (get-in a-graph [:opts :zone])
   :remote-composer-classpath     (get-in a-graph [:config :remote-composer-classpath])
   :local-angleddream-path (get-in a-graph [:config :local-angleddream-path])
   :remote-libs-path (get-in a-graph [:config :remote-libs-path])})

(defn build-items [g node md]
  "Add each metadata per node"
  (reduce #(add-attr %1 node (key %2) (val %2)) g md))

(defn build-annot [g nodes a-graph]
  "Annotate nodes w/ metadata"
  (reduce #(build-items %1 %2 (item-metadata %2 a-graph)) g nodes))

(defn anns [g a-graph]
  "Traverse graph g and annotate nodes w/ metadata"
  (let [t (bf-traverse g)] ;traverse graph to get list of nodes
    (build-annot g t a-graph)))

(defn is-bigquery?
  "Is the node a bigquery node?"
  [g node]
  (= "bq" (attr g node :type)))

(defn topic
  "Construct a topic"
  [project node]
  (str "projects/" project "/topics/" node))

(defn name-edge
  "name the edge"
  [g [in out]]
  (if (= 0 (in-degree g in))
    (source-topic-name in)
    (if (attr g out :error)
      (error-topic-name g in)
      (new-topic-name g in))))

(defn topic-edge
  "combine edge name and graph project with 2 strings to demonstrate variable args"
  [g edge conf]
  (topic (:project conf) (attr g edge :name)))

(defn apply-edge-metadata [the-graph attr-name metadata-fn & fn-args]
  "Apply attr-name to all edges for the function metadata-fn"
  (reduce #(add-attr-to-edges %1 attr-name (apply metadata-fn %1 %2 fn-args) [%2]) the-graph (edges the-graph)))

(defn name-edges [g conf]
  "Give a str to the :name attribute"
  (->
   g
   (apply-edge-metadata :name name-edge)
   (apply-edge-metadata :topic topic-edge conf)))

(defn predecessor-depends
  "Determine which resources a node depends on"
  [g node]
  (let [preds (predecessors g node)
        sources (u/filter-node-attrs g :exec :source preds)
        jobs (u/filter-node-attrs g :exec :pipeline preds)
        source-names (mapv #(str app-prefix "." %) sources)
        job-names (mapv #(str df-prefix "." %)  jobs)]
    (conj source-names job-names)))

(defn create-container-cluster
  "Create a Kubernetes cluster"
  [a-graph]
  (let [zone (get-in a-graph [:opts :zone])
        output (assoc (:cluster a-graph) :zone zone)]
    output))

(defn container-dependencies [g node conf]
  "return [{name:ip}] of containers that a dataflow job might depend on for external data"
  (let [containers (attr g node :container-deps)]
    (mapv #(assoc {} % (str "${googleappengine_app." % ".moduleName}-dot-" (:project conf) ".appspot.com")) containers)))

(defn join-containers [deps]
  "Join two container names"
  (if (> (count deps) 0)
    (clojure.string/join (flatten (interpose "," (map #(interpose "|" (first %)) deps))))))

(defn parent-error-enabled? [g node]
  "Determine if an error collector is enabled"
  (let [parent (first (predecessors g node))]
    (or (attr g parent :error-out))))

(defn create-container [g node conf]
  "Create a kubernetes container to run a service"
  (let [item_name (clojure.string/lower-case node)
        proj_name (:project conf)
        resource_version (attr g node :resource-version)
        zone (:region conf)
        image (attr g node :image)
        env_args (assoc (attr g node :args) :proj_name proj_name)]

    {item_name {:name item_name :container_name sink-container :resource_version [resource_version] :docker_image image :zone zone  :env_args env_args :external_port external-port}}))

(defn create-sink-container [g node conf]
  "Create a kubernetes node to read data from a pubsub and output it somewhere."
  (let [item_name (clojure.string/lower-case (str node "-sink"))
        proj_name (:project conf)
        resource_version (get-in conf [:config-file :config :sink-resource-version])
        sub_name (str (attr g (first (in-edges g node)) :name) (attr g node :sub-name))
        bucket_name (attr g node :bucket)
        zone (:region conf)
        replicas (or (attr g node :replicas) 1)
        sink_type (attr g node :sink_type)
        rsys_table (attr g node :rsys_table)
        rsys_pass (attr g node :rsys_pass)
        rsys_user (attr g node :rsys_user)
        dataset_name (attr g node :dataset_name)
        batch-size (or (attr g node :batch_size) sink-buffer-size)
        merge_insert (attr g node :merge_insert)
        depends-on (let    [s [(str "google_pubsub_subscription." sub_name)]]
                     (if bucket_name (conj s (str "google_storage_bucket." bucket_name))
                         s))
        error-topic (if (< 0 (count (out-edges g node))) (attr g (first (u/filter-edge-attrs g :type :error (out-edges g node))) :topic))
        output {item_name {:name item_name :resource_version [resource_version]
                           :depends_on depends-on
                           :optional_args {:replicas replicas}
                           :docker_image (get-in conf [:config-file :config :default-sink-docker-image]) :container_name sink-container :zone zone
                           :env_args {:num_retries sink-retries :batch_size batch-size :proj_name proj_name :sub_name sub_name :bucket_name bucket_name :rsys_pass rsys_pass
                                      :sink_type sink_type :dataset_name dataset_name :rsys_user rsys_user :rsys_table rsys_table :error_topic error-topic :merge_insert merge_insert}}}]
    output))

(defn create-sub [g edge node]
  "make a subscription for every node of type gcs based on the inbound edge [for now should only be 1 inbound edge]"
  (let [name (str (attr g edge :name) (attr g node :sub-name))
        topic (attr g edge :name)]
    {name {:name name :topic topic :depends_on [(str pt-prefix "." (attr g edge :name))]}}))

(defn create-subs [g node]
  "Create subscriptions"
  (apply merge (map #(create-sub g % node) (in-edges g node))))

(defn create-bucket [g node]
  "Create a google storage bucket"
  (if (and (= "gcs" (attr g node :type)) (attr g node :bucket))
    {(attr g node :bucket) {:name (attr g node :bucket) :force_destroy (or (attr g node :force_destroy) default-force-bucket-destroy) :location default-bucket-location}}))

(defn create-appengine-source
  "Creates a rest endpont and a single pubsub -- the only time we restrict to a single output"
  [node g conf]
  {node {:moduleName  node :version "init" :runtime "java7"
         :depends_on  [(str "google_pubsub_topic." node)]
         :gstorageKey (get-in conf [:config-file :config :appengine-gstoragekey]) :resource_version [(get-in conf [:config-file :config :source-resource-version])] :gstorageBucket gstoragebucket :scaling
         {:minIdleInstances default-min-idle :maxIdleInstances default-max-idle :minPendingLatency default-min-pending-latency :maxPendingLatency default-max-pending-latency}
         :topicName   (attr g (first (out-edges g node)) :topic)}})

#_(defn create-appengine-sink
    "Creates a sink using app engine"
    [node g conf]
    (let [item_name (clojure.string/lower-case (str node "-sink"))
          proj_name (:project conf)
          resource_version (get-in conf [:config-file :config :sink-resource-version])
          sub_name (str (attr g (first (in-edges g node)) :name) "_sub")
          bucket_name (attr g node :bucket)
          sink_type (attr g node :sink_type)
          rsys_table (attr g node :rsys_table)
          rsys_pass (attr g node :rsys_pass)
          rsys_user (attr g node :rsys_user)
          dataset_name (attr g node :dataset_name)
          merge_insert (attr g node :merge_insert)
          depends-on (let    [s [(str "google_pubsub_subscription." sub_name)]]
                       (if bucket_name (conj s (str "google_storage_bucket." bucket_name))
                           s))
          error-topic (if (< 0 (count (out-edges g node))) (attr g (first (u/filter-edge-attrs g :type :error (out-edges g node))) :topic))]
      {item_name {:moduleName item_name :version "init" :resource_version [resource_version]
                  :depends_on depends-on :threadsafe true :runtime "python27" :scriptName "main.app" :pythonUrlRegex  "/.*"
                  :gstorageKey (get-in conf [:config-file :config :appengine-sinkkey]) :gstorageBucket gstoragebucket :scaling
                  {:minIdleInstances default-min-idle :maxIdleInstances default-max-idle :minPendingLatency default-min-pending-latency :maxPendingLatency default-max-pending-latency}
                  :env_args    {:num_retries sink-retries :batch_size sink-buffer-size :proj_name proj_name :sub_name sub_name :bucket_name bucket_name :rsys_pass rsys_pass
                                :sink_type   sink_type :dataset_name dataset_name :rsys_user rsys_user :rsys_table rsys_table :error_topic error-topic :merge_insert merge_insert}}}))

(defn create-appengine-dep
  "Creates an external dependency using app engine"
  [node g conf]
  (let [item_name (clojure.string/lower-case node)
        proj_name (:project conf)
        resource_version (attr g node :resource-version)
        gstorageKey (attr g node :gstorageKey)
        gstorageBucket (attr g node :gstorageBucket)
        env_args (assoc (attr g node :args) :proj_name proj_name)
        script_name (attr g node :scriptName)]
    {item_name {:moduleName item_name :version "init" :env_args env_args :threadsafe true :runtime "python27"
                :gstorageKey gstorageKey :resource_version [resource_version] :gstorageBucket gstorageBucket :scriptName script_name :pythonUrlRegex "/.*"
                :scaling {:minIdleInstances default-min-idle :maxIdleInstances default-max-idle :minPendingLatency default-min-pending-latency :maxPendingLatency default-max-pending-latency}}}))

(defn create-dataflow-job                                ;build the right classpath, etc. composer should take all the jars in the classpath and glue them together like the transform-jar?
  "Create a dataflow job by traversing graph g"
  [g node conf]
  (let [output-edge (first (u/filter-not-edge-attrs g :type :error (out-edges g node)))
        input-edges (u/filter-not-edge-attrs g :type :error (in-edges g node))
        error-edge (first (u/filter-edge-attrs g :type :error (out-edges g node)))
        predecessor-depends (predecessor-depends g node)
        output-topic (if output-edge (attr g output-edge :topic))
        error-topic (attr g error-edge :topic)
        input-topics  (map #(attr g % :topic) input-edges)
        workerMachineType (or (attr g node :workerMachineType) (get-in conf [:config-file :config :default-pipeline-machine-type]))
        class angledream-class
        input-depends (map #(str pt-prefix "." %) (map #(attr g % :name) input-edges))
        output-depends (if output-edge (str pt-prefix "." (attr g output-edge :name)))
        error-depends (if error-edge (str pt-prefix "." (attr g error-edge :name)))
        container-deps (join-containers (container-dependencies g node conf))
        depends-on (remove nil? (flatten [(flatten [output-depends predecessor-depends error-depends]) input-depends]))
        class-jars (if-let [jar (attr g node :transform-jar)] (str (:remote-libs-path conf) "/" jar))
        classpath (filter some? [(get-in conf [:config-file :config :remote-composer-classpath])
                                 (or class-jars)])
        resource-hashes (filter some? (map #(u/hash-jar %) classpath))
        opt-map {:pubsubTopic       (clojure.string/join (interpose "," input-topics))
                 :pipelineName      node
                 :errorPipelineName error-topic                  ; :experiments "enable_streaming_scaling" ; :autoscalingAlgorithm "THROUGHPUT_BASED"
}
        opt-map (if-not (= (attr g node :type) "bq")
                  (assoc opt-map :outputTopics output-topic)
                  opt-map)
        opt-mapb (if-not (empty? resource-hashes) (assoc opt-map :stringHashes (clojure.string/join "," resource-hashes)) opt-map)
        bucket-opt-map {:bucket (attr g node :bucket)}
        bq-opts (if (is-bigquery? g node) (dissoc (dissoc (attrs g node) :type) :exec))
        optional-args (apply merge opt-mapb (get-in conf [:config-file :opts]) (if (:bucket bucket-opt-map) bucket-opt-map) bq-opts {:containerDeps container-deps} {:workerMachineType workerMachineType})
        out {:name          node
             :classpath     (clojure.string/join (interpose ":" classpath))
             :class         class
             :depends_on    depends-on
             :optional_args optional-args}]

    {node (if-not (empty? resource-hashes) (assoc out :resource_hashes resource-hashes) out)}))          ;NOTE: name needs to only have [- a-z 0-9] and must start with letter


(defn create-bq-dataset [g node]
  "Crete a dataset in bigquery"
  (let [ds (attr g node :bigQueryDataset)]
    {ds {:datasetId ds}}))

(defn create-bq-table [g node]
  "Create a bigquery table"
  (let [table (attr g node :bigQueryTable)]
    {table {:tableId    table
            :depends_on [(str "googlebigquery_dataset." (attr g node :bigQueryDataset))]
            :datasetId  (str "${googlebigquery_dataset." (attr g node :bigQueryDataset) ".datasetId}")
            :schemaFile (attr g node :bigQuerySchema)}}))

(defn create-dataflow-jobs [g conf]
  "Create a dataflow job"
  (apply merge (map #(create-dataflow-job g % conf) (u/filter-node-attrs g :exec :pipeline))))

(defn output-provider
  "Create a terraform output provider"
  [provider-map]
  (assoc (:provider provider-map) :region (get-in provider-map [:opts :zone])))

(defn output-pubsub [g]
  "Create a pubsub queue"
  (apply merge (map #(assoc-in {} [(attr g % :name) :name] (attr g % :name)) (edges g))))

(defn create-sources [g conf]
  "Create a service source in appengine"
  (apply merge (map #(create-appengine-source % g conf)
                    (u/filter-node-attrs g :exec :source))))

(defn output-bq-datasets [g]
  "Create an output dataset in bigquery"
  (apply merge (map #(create-bq-dataset g %)
                    (u/filter-node-attrs g :type "bq"))))

(defn output-bq-tables [g]
  "Output bigquery tables"
  (apply merge (map #(create-bq-table g %)
                    (u/filter-node-attrs g :type "bq"))))

(defn out-sinks [g]                                         ;was this ever used?
  (let [sinks (u/filter-node-attrs g :exec :sink)]
    (filter #(or (not (attr g % :error))
                 (and (attr g % :error-out) (parent-error-enabled? g %))) sinks)))

#_(defn output-sinks [g conf]
    (apply merge (map #(create-appengine-sink % g conf) (u/filter-node-attrs g :exec :sink))))

(defn output-sinks [g conf]
  "Create sinks for ourput"
  (apply merge (map #(create-sink-container g % conf) (u/filter-node-attrs g :exec :sink))))

(defn output-containers [g conf]
  "Create generic kubernetes containers for output data"
  (let [containers (u/filter-node-attrs g :exec :container)]
    (apply merge (map #(create-appengine-dep % g conf) containers))))

(defn output-subs [g]
  "Crete subscriptions for outputs"
  (apply merge (map #(create-subs g %) (u/filter-node-attrs g :type "gcs"))))

(defn output-buckets [g]
  "Create storage buckets for output"
  (apply merge (map #(create-bucket g %) (nodes g))))

(defn output-container-cluster
  "Create a kuberenets cluster to output to "
  [a-graph]
  {:hx_fstack_cluster (create-container-cluster a-graph)})

(defn add-error-sink-node [g parent conf]
  "Add an error handling node in the DAG and assocated sink"
  (let [name (str parent "-error")]
    (-> g
        (add-nodes name)
        (add-attr name :type "gcs")
        (add-attr name :exec :sink)
        (add-attr name :bucket name)
        (add-attr name :error true)
        (add-attr name :batch_size (get-in conf [:config-file :config :default-error-bucket-batch-size]))
        #_(add-attr name :run (parent-error-enabled? g name))
        (add-edges [parent name])
        (add-attr-to-edges :type :error [[parent name]]))))

(defn add-containers [g a-graph]
  "Add service containers to DAG"
  (reduce #(add-nodes %1 (key %2)) g (:containers a-graph)))

(defn name-subs [g]
  (reduce #(add-attr %1 %2 :sub-name (sub-suffix)) g (u/filter-node-attrs g :type "gcs")))

(defn add-error-sinks
  "Add error sinks to every non-source item on the graph. Sources don't have errors because they should return a 4xx or 5xx when something gone wrong"
  [g conf]
  (let [connected (filter #(> (in-degree g %1) 0) (nodes g))]
    (reduce #(add-error-sink-node %1 %2 conf) g connected)))

(defn create-dag
  "the most important fn-- created directed graph, use this to feed all other fns"
  [a-graph conf]
  (let [g (digraph (into {} (map (juxt :origin :targets) (:edges a-graph))))]
    ;decorate nodes
    (-> g
        (add-containers a-graph)
        (anns a-graph)
        (add-error-sinks conf)
        (name-edges conf)
        (name-subs)))                                    ;return the graph?
)

(defn create-terraform-json
  "Generate the terraform file to create infrastructure"
  [a-graph]
  (let [conf (config-md a-graph)
        g (create-dag a-graph conf)
        goo-provider {:google (output-provider a-graph)}
        cli-provider {:googlecli (output-provider a-graph)}
        bq-provider {:googlebigquery (output-provider a-graph)}
        app-provider {:googleappengine (output-provider a-graph)}
        pubsubs {:google_pubsub_topic (output-pubsub g)}
        subscriptions {:google_pubsub_subscription (output-subs g)}
        buckets {:google_storage_bucket (output-buckets g)}
        bigquery-datasets {:googlebigquery_dataset (output-bq-datasets g)}
        bigquery-tables {:googlebigquery_table (output-bq-tables g)}
        dataflows {:googlecli_dataflow (create-dataflow-jobs g conf)}
        container-cluster {:google_container_cluster (output-container-cluster a-graph)}
        sources {:googleappengine_app (apply merge (create-sources g conf) (output-containers g conf))}
        controllers {:googlecli_container_replica_controller (output-sinks g conf)}
        combined {:provider (merge goo-provider cli-provider app-provider bq-provider)
                  :resource (merge pubsubs subscriptions container-cluster controllers sources buckets dataflows bigquery-datasets bigquery-tables)}
        filtered-out (u/remove-nils combined)
        out (clojure.string/trim (generate-string filtered-out {:pretty true}))]

    (str "{" (subs out 1 (- (count out) 2)) "}")))                        ;trim first [ and last ] from json


(defn output-terraform-file
  "Output the terraform json"
  [a-graph file]
  (spit file (create-terraform-json a-graph) :create true :append false :truncate true))

(defn merge-graph-items [g1 g2]
  "Merge graphs for any traversal"
  (-> g1
      (update :opts #(merge-with conj % (:opts g2)))
      (update :containers #(merge-with conj % (:containers g2)))
      (update :provider #(merge-with conj % (:provider g2)))
      (update :cluster #(merge-with conj % (:cluster g2)))
      (update :config #(merge-with conj % (:config g2)))
      (update :pipelines #(merge-with conj % (:pipelines g2)))
      (update :edges #(concat % (:edges g2)))
      (update :sources #(merge-with conj % (:sources g2)))
      (update :sinks #(merge-with conj % (:sinks g2)))))

(defn validate-config [a-graph]
  "Make sure the config has necessary values"
  (s/validate cs/base a-graph))

(defn read-graphs [input-files]
  "returns a graph of all the subgraph files, merged"
  (let [inputs (doall (mapv (comp read-string slurp) input-files))]
    (-> (reduce #(merge-graph-items %1 %2) {} inputs)
        validate-config)))

(defn read-and-create
  "Read the config file and generate terraform"
  [input output]
  (output-terraform-file (read-graphs input) output))

(defn view-graph
  "Visualize DAG"
  [input]
  (loom.io/view (create-dag (read-graphs input) (config-md (read-graphs input)))))

(defn test-cluster [a-graph]
  "given a config map, test a cluster. returns [graph input-pipes]"
  (do
    (swap! sim/this-conf (fn [x] (config-md a-graph)))
    (swap! sim/execute-timestamp (fn [x] (.toString (ti/now))))
    (let [dag (create-dag a-graph @sim/this-conf)]
      (sim/setup-jars dag @sim/this-conf)
      (sim/compose-cluster dag))))

(defn file-tester [a-graph]
  "tests against test files in config.clj"
  (let [g (test-cluster a-graph)
        pipes (doall (u/get-all-node-or-edge-attr g :in-chan))
        in-files (doall (u/get-all-node-or-edge-attr g :test-input))]
    (doseq [pipe (keys pipes)]
      (doseq [data (doall (parse-stream (clojure.java.io/reader (get in-files pipe)) true))]
        (put! (get pipes pipe) (sim/handle-message data))))))

(def cli-options
  [["-c" "--config CONFIG" "cinna-delimited paths to .clj config file for pipeline e.g. 'test-files/config1.clj,test-files/config2.clj' "]
   ["-o" "--output OUTPUT" "path to output terraform file"]
   ["-v" "--view" "view visualization, requires graphviz installed"]
   ["-s" "--sim" "simulate cluster, running input scripts and producing file output"]
   ["-tf" "--testfile TESTFILE" "test file for simulator, defines local testing environment"]
   #_["-d" "--dbg" "print simulator debugging info"]])

(defn -main
  "Entry Point"
  [& args]
  (let [parsed (cl/parse-opts args cli-options)
        opts (:options parsed)
        conf (clojure.string/split (:config opts) #",")]
    (do
      (println opts)
      (if-not opts (println (:summary parsed)))
      (if (:errors parsed) (println "ERROR:" (:errors parsed))
          (if (:view opts) (view-graph conf)
              (if (:sim opts)
                (do
                  (file-tester (read-graphs (conj conf (:testfile opts))))
                  (Thread/sleep 5000)
                  (println "Test output files created"))
                (do
                  (read-and-create conf (:output opts))
                  (println "Terraform file created"))))))))

