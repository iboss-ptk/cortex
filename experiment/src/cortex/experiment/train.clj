(ns cortex.experiment.train
  (:require [clojure.java.io :as io]
            [think.parallel.core :as parallel]
            [cortex.optimize :as opt]
            [cortex.optimize.adam :as adam]
            [cortex.nn.execute :as execute]
            [cortex.nn.compute-binding :as compute-binding]
            [cortex.nn.network :as network]
            [cortex.nn.traverse :as traverse]
            [cortex.graph :as graph]
            [cortex.util :as util]
            [cortex.loss.core :as loss])
  (:import [java.io File]))

(def default-network-filestem "trained-network")
(def trained-networks-folder "trained-networks/")

(set! *warn-on-reflection* true)

(defn load-network
  "Loads a map of {:cv-loss :network-description}."
  [network-filename]
  (if (.exists (io/file network-filename))
    (util/read-nippy-file network-filename)
    (throw (ex-info "Saved network not found." {:filename network-filename}))))


(defn save-network
  "Saves a trained network out to the filesystem."
  [network network-filename]
  (util/write-nippy-file network-filename network)
  network)

(defn get-labels
  "returns the predicted labels for the given network and dataset"
  ([new-network  batch-size test-ds]
   (execute/run new-network test-ds
     :batch-size batch-size
     :loss-outputs? true))
  ([new-network  batch-size context test-ds]
   (execute/run new-network test-ds
     :batch-size batch-size :context context
     :loss-outputs? true)))

(defn network-loss
  "calculate the loss on the given network and dataset. Returns a map with
  the raw loss (per label)  and the sum of loss for all labels"
  [new-network labels test-ds]
  (let [loss-fn (execute/execute-loss-fn new-network labels test-ds)]
    {:raw-loss loss-fn :loss-sum (apply + (map :value loss-fn))}))

(defn default-network-loss-eval-fn
  "Evaluate the network using its current loss terms"
  [simple-loss-print? new-network test-ds batch-size]
  (let [labels (get-labels new-network batch-size test-ds)
        {:keys [raw-loss loss-sum]} (network-loss new-network labels test-ds)]
    (when-not simple-loss-print?
      (println (loss/loss-fn->table-str raw-loss)))
    loss-sum))

(defn default-network-test-fn
  "Test functions take two map arguments, one with global information and one
  with information local to the epoch. The job of a test function is to return a
  map indicating if the new network is indeed the best one and the network with
  enough information added to make comparing networks possible.
    {:best-network? boolean
     :network (assoc new-network :whatever information-needed-to-compare).}"
  ;; TODO: No need for context here.
  [loss-val-fn
   loss-compare-fn
   ;; global arguments
   {:keys [batch-size context]}
   ;per-epoch arguments
   {:keys [new-network old-network test-ds]} ]
  (let [batch-size (long batch-size)
        loss-val (double (loss-val-fn new-network test-ds batch-size))
        current-best-loss (if-let [best-loss (get old-network :cv-loss)]
                            (if (sequential? best-loss)
                              (apply + (map :value best-loss))
                              (try
                                (double best-loss)
                                (catch Throwable e
                                  nil))))
        best-network? (or (nil? current-best-loss)
                          (loss-compare-fn (double loss-val)
                                           (double current-best-loss)))
        updated-network (assoc new-network :cv-loss (if best-network?
                                                      loss-val
                                                      current-best-loss))
        epoch (get new-network :epoch-count)]
    (println (format "Loss for epoch %3d: (current) %.8f (best) %.8f%s"
                     epoch loss-val current-best-loss
                     (if best-network? " [new best]" "")))
    {:best-network? best-network?
     :network updated-network}))

(defn- per-epoch-fn
  [test-fn network-filename training-context epoch-args]
  (let [test-results (test-fn training-context epoch-args)]
    (when (:best-network? test-results)
      (save-network (:network test-results) network-filename))
    (:network test-results)))

(defn backup-trained-network
  [network-filestem]
  (let [network-filename (str network-filestem ".nippy")]
    (when (.exists (io/file network-filename))
      (let [backup-filename (->> (rest (range))
                                 (map #(format "%s%s-%s.nippy" trained-networks-folder network-filestem %))
                                 (remove #(.exists (io/file %)))
                                 (first))]
        (io/make-parents backup-filename)
        (io/copy (io/file network-filename)
                 (io/file backup-filename))))))


(defn- create-n-callable-fn
  [src-fn max-call-count]
  (if max-call-count
    (let [counter (atom 0)
          max-call-count (long max-call-count)]
      (fn []
        (when (< @counter max-call-count)
          (swap! counter inc)
          (src-fn))))
    src-fn))


(defn- to-epoch-seq-fn
  [item epoch-count]
  (if-not (fn? item)
    (parallel/create-next-item-fn
     (let [retval (if (map? (first item))
                    (repeat item)
                    item)]
       (if epoch-count
         (take epoch-count retval)
         retval)))
    (create-n-callable-fn item epoch-count)))

(defn- recur-train-network
  [network train-ds-fn test-ds-fn optimizer train-fn epoch-eval-fn]
  ;[{:keys [network train-ds-fn test-ds-fn optimizer train-fn epoch-eval-fn] :as recur-args}]
  (let [train-data (train-ds-fn)
        test-data (test-ds-fn)
        old-network network]
    (when (and train-data test-data)
      (let [{:keys [network optimizer]} (train-fn network train-data optimizer)
            epoch-args {:new-network (update network :epoch-count inc)
                        :old-network old-network :train-ds train-data
                        :test-ds test-data}
            network (epoch-eval-fn epoch-args)]
        (cons network
              (lazy-seq
               (recur-train-network network train-ds-fn test-ds-fn
                                    optimizer train-fn epoch-eval-fn)))))))


(defn train-n
  "Given a network description, start training from scratch or given a trained
  network continue training. Keeps track of networks that are actually improving
  against a test-ds.

  Networks are saved with a `:cv-loss` that is set to the best cv loss so far.

  This system expects a dataset with online data augmentation so that it is
  effectively infinite although the cross-validation and holdout sets do not
  change. By default, the best network is saved to: `trained-network.nippy`

  Note, we have to have enough memory to store the cross-validation dataset
  in memory while training.

  Every epoch a test function is called with these 2 map arguments:

  (test-fn global-context epoch-context)

  It must return a map containing at least:
    {:best-network? true if this is the best network
     :network The new network with any extra information needed for comparison assoc'd onto it.}

  If epoch-count is provided then we stop training after that many epochs else
  we continue to train forever."
  [network train-ds test-ds
   & {:keys [batch-size epoch-count
             network-filestem
             optimizer
             reset-score
             force-gpu?
             simple-loss-print?
             test-fn
             context]
      :or {batch-size 128
           network-filestem default-network-filestem
           reset-score false}}]
  (let [context (or context (execute/compute-context))]
    (execute/with-compute-context context
      (let [optimizer (or optimizer (adam/adam))
            context (execute/compute-context)
            network-filename (str network-filestem ".nippy")
            ;; If someone is training with an infinite data sequence they have to
            ;; actually pass in a function that when called returns the next epoch
            ;; of data.  This is the only way so far to avoid 'holding onto head'
            ;; when the number of epochs rises.
            train-ds-fn (to-epoch-seq-fn train-ds epoch-count)
            test-ds-fn (to-epoch-seq-fn test-ds epoch-count)
            network (if (vector? network)
                      (do
                        (backup-trained-network network-filestem)
                        (network/linear-network network))
                      (if reset-score
                        (assoc network :cv-loss {})
                        network))
            network (if (number? (get network :epoch-count))
                      network
                      (assoc network :epoch-count 0))
            train-fn #(execute/train %1 %2
                                     :batch-size batch-size
                                     :optimizer %3
                                     :context context)
            training-context {:batch-size batch-size :context context}
            test-fn  (or test-fn
                         ;;Normally if the loss goes down then this is the best network
                         (partial default-network-test-fn (partial default-network-loss-eval-fn simple-loss-print?) <))
            epoch-eval-fn (partial per-epoch-fn test-fn network-filename training-context)]
        (println "Training network:")
        (network/print-layer-summary network (traverse/training-traversal network))
        (->> (recur-train-network network train-ds-fn test-ds-fn optimizer train-fn epoch-eval-fn)
             last)))))


(defn print-trained-networks-summary
  "Prints a summary of the different networks trained so far.
  Respects an (optional) `network-filestem`."
  [& {:keys [network-filestem
             cv-loss->number
             cv-loss-display-precision
             extra-keys]
      :or {network-filestem default-network-filestem
           cv-loss->number #(apply + (vals %))
           cv-loss-display-precision 3}}]
  (let [cv-loss-format-string (format "%%.%sf" cv-loss-display-precision)]
    (->> trained-networks-folder
         io/file
         file-seq
         (filter #(let [n (.getPath ^File %)]
                    (and (.contains ^String n (.concat ^String trained-networks-folder
                                                       ^String network-filestem))
                         (.endsWith ^String n ".nippy"))))
         (map (fn [f] [f (util/read-nippy-file f)]))
         (map (fn [[f network]] (assoc network :filename (.getName ^File f))))
         (map (fn [network] (update network :cv-loss cv-loss->number)))
         (sort-by :cv-loss)
         (map (fn [network] (update network :cv-loss #(format cv-loss-format-string %))))
         (clojure.pprint/print-table (concat [:filename :cv-loss :parameter-count] extra-keys)))))
