(ns comportexviz.plots
  (:require [reagent.core :as reagent :refer [atom]]
            [monet.canvas :as c]
            [comportexviz.plots-canvas :as plt]
            [comportexviz.helpers :refer [canvas resizing-canvas
                                          window-resize-listener]]
            [comportexviz.bridge.channel-proxy :as channel-proxy]
            [comportexviz.selection :as sel]
            [org.nfrac.comportex.core :as core]
            [org.nfrac.comportex.util :as util :refer [round]]
            [clojure.string :as str]
            [goog.dom :as dom]
            [cljs.core.async :as async :refer [chan put! <!]])
  (:require-macros [cljs.core.async.macros :refer [go]]))

(defprotocol PCompressible
  (compress [this factor]))

(defprotocol PBucketed
  (buckets [this])
  (bucket-size [this]))

(defprotocol PCapped
  (max-count [this]))

(deftype SequenceCompressor [bucket-size* fcompress xs unfilled-bucket]
  PBucketed
  (bucket-size [_]
    bucket-size*)
  (buckets [_]
    (vec xs))

  PCompressible
  (compress [_ factor]
    (SequenceCompressor. (* factor bucket-size*) fcompress
                         (->> xs
                              (partition factor)
                              (mapv (partial apply fcompress)))
                         unfilled-bucket))

  ICollection
  (-conj [_ x]
    (let [bucket (conj unfilled-bucket x)]
      (if (< (count bucket) bucket-size*)
        (SequenceCompressor. bucket-size* fcompress xs bucket)
        (SequenceCompressor. bucket-size* fcompress
                             (conj xs (apply fcompress bucket)) (empty xs)))))

  ICounted
  (-count [_]
    (count xs))

  ISeqable
  (-seq [_]
    (seq xs)))

(deftype SequenceCompressorCapped [max-bucket-count fcompress seq-compressor]
  PBucketed
  (bucket-size [_]
    (bucket-size seq-compressor))
  (buckets [_]
    (buckets seq-compressor))

  PCompressible
  (compress [_ factor]
    (SequenceCompressorCapped. max-bucket-count fcompress
                               (compress seq-compressor factor)))

  PCapped
  (max-count [_]
    max-bucket-count)

  ICollection
  (-conj [_ x]
    (let [r (SequenceCompressorCapped. max-bucket-count fcompress
                                       (conj seq-compressor x))]
      (if (< (count r) max-bucket-count)
        r
        (compress r 2))))

  ICounted
  (-count [_]
    (count seq-compressor))

  ISeqable
  (-seq [_]
    (seq seq-compressor)))

(defn sequence-compressor
  "A sequence builder that does not necessarily grow on `conj`.
  Individual items are the result of compressing buckets of values
  into a single value. Compress the sequence or check its bucket size
  via methods. Specify a `max-bucket-count` to make it automatically
  recompress as it grows.

  Extract the compressed sequence via `seq` or `buckets`.

  `fcompress` must take an arbitrary number of args and must return a
  value of the same format, i.e. a value that will later be used as a
  fcompress arg."
  ([fcompress]
   (SequenceCompressor. 1 fcompress [] []))
  ([max-bucket-count fcompress]
   (SequenceCompressorCapped. max-bucket-count fcompress
                              (sequence-compressor fcompress))))

(defn mean
  [xs]
  (/ (apply + xs) (count xs)))

(defn aggregate-by
  [f maps]
  (let [ks (keys (first maps))]
    (->>
     (for [k ks]
       [k (f (map #(get % k) maps))])
     (into {}))))

(defn stacked-ts-plot
  [ctx col-state-freqs-log series-keys series-colors]
  (let [bucket-size (bucket-size col-state-freqs-log)
        buckets (buckets col-state-freqs-log)
        n-timesteps (* bucket-size (max-count col-state-freqs-log))
        ncol (:size (peek buckets))
        v-max (* ncol 0.06)
        cnv (.-canvas ctx)
        plot-size {:w (- (.-width cnv) 25)
                   :h (- (.-height cnv) 18)}
        plot (plt/xy-plot ctx plot-size
                          [0 n-timesteps]
                          [v-max 0])
        ]

    (c/clear-rect ctx {:x 0 :y 0 :w (.-width cnv) :h (.-height cnv)})
    (c/stroke-width ctx 0)
    (doseq [[i x] (plt/indexed buckets)]
      (reduce (fn [from-y k]
                (let [val (get x k)]
                  (c/fill-style ctx (series-colors k))
                  (plt/rect! plot (* i bucket-size) from-y
                             bucket-size val)
                  (+ from-y val)))
              0 series-keys))
    (plt/frame! plot)
    (c/fill-style ctx "black")
    (c/stroke-style ctx "black")
    (c/stroke-width ctx 1)
    ;; draw x labels
    (c/text-baseline ctx :top)
    (doseq [x (range 0 (inc n-timesteps)
                     (/ n-timesteps 8))
            :let [[xpx ypx] (plt/->px plot x 0)]]
      (doto ctx
        (c/begin-path)
        (c/move-to xpx ypx)
        (c/line-to xpx (+ ypx 5))
        (c/stroke))
      (c/text ctx {:x xpx
                   :y (+ ypx 5)
                   :text x}))
    ;; draw y labels
    (c/text-baseline ctx :middle)
    (let [labx n-timesteps]
      (doseq [f [0 0.02 0.04]
              :let [y (* ncol f)
                    [xpx ypx] (plt/->px plot labx y)]]
        (doto ctx
          (c/begin-path)
          (c/move-to xpx ypx)
          (c/line-to (+ xpx 5) ypx)
          (c/stroke))
        (c/text ctx {:x (+ xpx 10)
                     :y ypx
                     :text y})))
    ))

(defn empty-col-state-freqs-log
  []
  (sequence-compressor 200
                       (fn [& col-state-freqs-seq]
                         (aggregate-by mean col-state-freqs-seq))))

(defn ts-freqs-plot-cmp
  [_ _]
  (let [size-invalidates-c (async/chan)]
    (fn [col-state-freqs-log series-colors]
      [:div nil
       [window-resize-listener size-invalidates-c]
       [resizing-canvas
        {:style {:width "100%"
                 :height 180}}
        []
        (fn [ctx]
          (stacked-ts-plot ctx col-state-freqs-log
                           [:active :active-predicted :predicted]
                           series-colors))
        size-invalidates-c]])))

(def excitation-colors
  {:proximal-unstable :active
   :proximal-stable :active-predicted
   :boost :highlight
   :temporal-pooling :temporal-pooling
   :distal :predicted
   })

(def excitation-order
  [:proximal-unstable
   :proximal-stable
   :boost
   :temporal-pooling
   :distal])

(defn viz-rgn-shades
  [step-template]
  (let [srcs (concat (keys (:senses @step-template))
                     (keys (:regions @step-template)))]
    (zipmap srcs (range -0.3 0.31 (/ 1.0 (count srcs))))))

(defn- abs [x] (if (neg? x) (- x) x))

(defn draw-cell-excitation-plot!
  [ctx breakdowns step-template sel-col series-colors]
  (let [width-px (.-width (.-canvas ctx))
        height-px (.-height (.-canvas ctx))
        plot-size {:w width-px
                   :h 200}

        src-shades (viz-rgn-shades step-template)
        y-max (* 1.1 (apply max (map :total (vals breakdowns))))
        x-lim [-0.5 (+ (count breakdowns) 3)] ;; space for legend
        y-lim [y-max 0]
        bot-lab-y (- (* y-max 0.02))
        draw-cell-bar
        (fn [plot x-coord bd labels?]
          (let [series (for [k excitation-order
                             :let [v (get bd k)]
                             [src x] (if (map? v)
                                       (sort-by (comp src-shades key) v)
                                       {nil v})
                             :when (and x (pos? x))]
                         [k src x])]
            (c/stroke-style ctx "black")
            (reduce (fn [offset [k src x]]
                      (let [color (excitation-colors k)
                            shade (if src (src-shades src) 0.0)]
                        (c/fill-style ctx (get series-colors color))
                        (plt/rect! plot x-coord offset 0.5 x)
                        (when-not (zero? shade)
                          (c/fill-style ctx (if (pos? shade) "white" "black"))
                          (c/alpha ctx (abs shade))
                          (plt/rect! plot x-coord offset 0.25 x)
                          (c/alpha ctx 1.0))
                        (when labels?
                          (c/fill-style ctx "black")
                          (let [labs (concat (str/split (name k) #"-")
                                             (if src [(str "(" (name src) ")")]))]
                            (plt/texts! plot (+ x-coord 0.5) (+ offset (* 0.5 x))
                                        labs 10)))
                        (+ offset (or x 0))))
                    0.0
                    series)))]
    (c/save ctx)
    (c/clear-rect ctx {:x 0 :y 0 :w width-px :h height-px})
    (let [plot (plt/xy-plot ctx plot-size x-lim y-lim)]
      (doseq [[i [cell-id bd]] (->> breakdowns
                                    (sort-by key)
                                    (sort-by (comp :total val) >)
                                    (map-indexed vector))
              :let [x-coord i
                    [col _] cell-id
                    total-exc (:total bd)]]
        (draw-cell-bar plot x-coord bd false)
        (when (= col sel-col)
          (c/fill-style ctx (:highlight series-colors))
          (plt/rect! plot (- x-coord 0.25) -100 1.0 100))
        (c/fill-style ctx "black")
        (plt/text! plot x-coord (+ total-exc 0.5) total-exc)
        (plt/text-rotated! plot x-coord bot-lab-y (if (= col sel-col) cell-id col)))
      ;; draw legend
      (let [sep-x (count breakdowns)
            leg-x (inc sep-x)
            key-bd* (->
                     (apply util/deep-merge-with + (vals breakdowns))
                     (core/update-excitation-breakdown #(if (pos? %) 1.0 0.0)))
            key-bd (core/update-excitation-breakdown key-bd* #(* % (/ y-max (:total key-bd*))))]
        (c/fill-style ctx (:background series-colors))
        (plt/rect! plot sep-x 0 (- (second x-lim) sep-x) y-max)
        (c/text-align ctx :center)
        (draw-cell-bar plot leg-x key-bd true)
        (c/fill-style ctx "black")
        (c/text-align ctx :left)
        (plt/text-rotated! plot leg-x bot-lab-y "KEY")
        (plt/frame! plot)))
    (c/restore ctx)))

(defn fetch-excitation-data!
  [excitation-data sel into-journal local-targets]
  (let [{:keys [model-id bit] :as sel1} (first (filter sel/layer sel))
        [region layer] (sel/layer sel1)]
    (if layer
      (let [response-c (async/chan)]
        (put! @into-journal
              [:get-cell-excitation-data model-id region layer bit
               (channel-proxy/register! local-targets
                                        response-c)])
        (go
          (reset! excitation-data (<! response-c))))
      (reset! excitation-data {}))))

(defn cell-excitation-plot-cmp
  [_ selection _ _ _ into-journal local-targets]
  (let [excitation-data (atom {})]
    (add-watch selection :fetch-excitation-data
               (fn [_ _ _ sel]
                 (fetch-excitation-data! excitation-data sel into-journal
                                         local-targets)))

    (fetch-excitation-data! excitation-data @selection into-journal
                            local-targets)

    (fn [step-template _ series-colors region-key layer-id _ _]
      [canvas
       {}
       300
       240
       [excitation-data]
       (fn [ctx]
         (let [sel1 (first (filter sel/layer @selection))
               [sel-rgn sel-lyr] (sel/layer sel1)
               sel-col (when (and sel-lyr
                                  (= region-key sel-rgn)
                                  (= layer-id sel-lyr))
                         (:bit sel1))]
           (draw-cell-excitation-plot! ctx @excitation-data step-template
                                       sel-col series-colors)))
       nil])))

(defn draw-transitions-plot!
  [ctx {:keys [sdr-transitions sdr-label-counts matching-sdrs title]}]
  (let [lc-sdrs (set (:learn matching-sdrs))
        ac-sdrs (set (:active matching-sdrs))
        pc-sdrs (set (:pred matching-sdrs))
        title-px 20
        width-px (.-width (.-canvas ctx))
        height-px (- (.-height (.-canvas ctx)) title-px)
        y-scale (/ height-px (max 24 (inc (count sdr-label-counts))))
        sdr-max (->> (vals sdr-label-counts)
                     (map (fn [label-counts]
                            (reduce + (vals label-counts))))
                     (reduce max)
                     (max 5))
        x-scale (/ width-px sdr-max)
        mid-x (quot width-px 2)
        label-width 50
        label-height 14]
    (c/save ctx)
    (c/clear-rect ctx {:x 0 :y 0 :w width-px :h height-px})
    (c/translate ctx mid-x (+ title-px (quot label-height 2)))
    ;; draw title
    (let [title-y (- 0 (quot label-height 2) 4)]
      (c/text-align ctx :center)
      (c/text-baseline ctx :bottom)
      (c/text ctx {:x 0
                   :y title-y
                   :text title})
      (c/text ctx {:x (* mid-x 0.8)
                   :y title-y
                   :text "forward"})
      (c/text ctx {:x (- (* mid-x 0.8))
                   :y title-y
                   :text "back"}))
    ;; draw transitions
    (c/stroke-style ctx "hsl(210,50%,50%)")
    (c/stroke-width ctx 4)
    (c/alpha ctx 0.4)
    (doseq [[from-sdr to-sdrs] sdr-transitions
            :let [from-y (* y-scale from-sdr)]
            to-sdr to-sdrs
            :let [to-y (* y-scale to-sdr)
                  mid-y (/ (+ to-y from-y) 2)
                  off-x (* 1.0 width-px
                           (/ (- to-y from-y)
                              height-px))]]
      (doto ctx
        (c/begin-path)
        (c/move-to 0 from-y)
        (c/quadratic-curve-to off-x mid-y
                              0 to-y)
        (c/stroke)))
    (c/alpha ctx 1.0)
    (c/stroke-width ctx 1)
    ;; draw states
    (c/stroke-style ctx "#aaa")
    (c/text-baseline ctx :middle)
    (doseq [[sdr label-counts] sdr-label-counts
            :let [y (* y-scale sdr)
                  sdr-total (reduce + (vals label-counts))
                  sdr-width (* x-scale sdr-total)]]
      (doto ctx
        (c/fill-style (cond
                        (lc-sdrs sdr) "#bfb"
                        (ac-sdrs sdr) "#fbb"
                        (pc-sdrs sdr) "#bbf"
                        :else "#ddd"))
        (c/alpha 0.6)
        (c/rounded-rect {:x (- (quot sdr-width 2))
                         :y (- y (quot label-height 2))
                         :w sdr-width
                         :h label-height
                         :r (quot label-height 2)})
        (c/fill)
        (c/alpha 1.0)
        (c/fill-style "#000"))
      (reduce (fn [offset [label n]]
                (c/text ctx {:x (* x-scale (+ offset (quot n 2)))
                             :y y
                             :text (str label)})
                (+ offset n))
              (- (quot sdr-total 2))
              label-counts))
    (c/restore ctx)))

(defn find-matching-sdrs
  [cells cell-sdr-fracs threshold]
  (->> (map cell-sdr-fracs cells)
       (apply merge-with +)
       (filter (fn [[_ vote]] (>= vote threshold)))
       (sort-by val <)))

(defn- freqs->fracs
  [freqs]
  (let [total (reduce + (vals freqs))]
    (util/remap #(/ % total) freqs)))

(defn transitions-plot-builder
  [steps step-template selection into-journal local-targets]
  (let [fetch-transitions-data
        (fn [state-val sel]
          (when-let [[region layer] (sel/layer sel)]
            (let [model-id (:model-id sel)
                  cell-sdr-counts (get-in state-val [:cell-sdr-counts
                                                     [region layer]])
                  response-c (async/chan)]
              (put! @into-journal [:get-transitions-data
                                   model-id region layer cell-sdr-counts
                                   (channel-proxy/register!
                                    local-targets response-c)])
              response-c)))
        state (atom {:cell-sdr-counts {}
                     :sdr-label-counts {}
                     :matching-sdrs {}})
        plot-data (atom nil)]
    (add-watch
     steps ::update-sdrs-transitions-and-labels
     (fn [_ _ _ [step]]
       (when-let [model-id (:model-id step)]
         (doseq [[region layer-map] (:regions @step-template)
                 layer (keys layer-map)
                 :let [response-c (async/chan)]]
           (put! @into-journal [:get-cells-by-state model-id
                                region layer
                                (channel-proxy/register! local-targets
                                                         response-c)])
           (go
             (let [cells-by-state (<! response-c)
                   lc (:learn-cells cells-by-state)
                   ac (:active-cells cells-by-state)
                   pc (:pred-cells cells-by-state)
                   threshold (get-in @step-template [:regions region layer
                                                     :spec :seg-learn-threshold])
                   cell-sdr-fracs (->> (get-in @state [:cell-sdr-counts
                                                       [region layer]])
                                       (util/remap freqs->fracs))
                   sdrs-lc-votes (find-matching-sdrs lc cell-sdr-fracs threshold)
                   sdrs-ac-votes (find-matching-sdrs ac cell-sdr-fracs threshold)
                   sdrs-pc-votes (find-matching-sdrs pc cell-sdr-fracs threshold)
                   learn-sdrs (or (seq (map key sdrs-lc-votes))
                                  [(count (get-in @state [:sdr-label-counts
                                                          [region layer]]))])]
               (swap! state
                      (fn [state-val]
                        (let [inc-learn-sdrs (partial merge-with +
                                                      (zipmap learn-sdrs
                                                              (repeat 1)))
                              label (:label (:input-value step))
                              inc-label (partial merge-with + {label (/ 1 (count learn-sdrs))})]
                          (-> state-val
                              (assoc-in [:matching-sdrs
                                         [region layer model-id]]
                                        {:learn learn-sdrs
                                         :active (map key sdrs-ac-votes)
                                         :pred (map key sdrs-pc-votes)})
                              (update-in [:cell-sdr-counts
                                          [region layer]]
                                         (fn [m]
                                           (util/update-each (or m {}) lc
                                                             inc-learn-sdrs)))
                              (update-in [:sdr-label-counts
                                          [region layer]]
                                         (fn [m]
                                           (reduce (fn [m learn-sdr]
                                                     (update m learn-sdr
                                                             inc-label))
                                                   m
                                                   learn-sdrs)))))))
               (let [sel (first @selection)
                     model-id (:model-id sel)]
                 (when (= [region layer] (sel/layer sel))
                   (go
                     (let [x (<! (fetch-transitions-data @state sel))]
                       (swap! plot-data assoc
                              :sdr-transitions x
                              :sdr-label-counts (get-in @state [:sdr-label-counts
                                                                [region layer]])
                              :matching-sdrs (get-in @state [:matching-sdrs
                                                             [region layer model-id]])
                              :title (str (name region) " " (name layer)))))))
               ))))))
    (add-watch selection ::transitions-plot
               (fn [_ _ [old] [sel]]
                 (when-let [[region layer] (sel/layer sel)]
                   (let [model-id (:model-id sel)
                         matching-sdrs (get-in @state [:matching-sdrs
                                                       [region layer model-id]])
                         sdr-label-counts (get-in @state [:sdr-label-counts
                                                          [region layer]])]
                     (if (not= (sel/layer sel) (sel/layer old))
                       ;; switching layers, request new data
                       (go
                         ;; immediate update from local state while waiting:
                         (swap! plot-data assoc
                                :sdr-transitions {}
                                :sdr-label-counts sdr-label-counts
                                :matching-sdrs matching-sdrs
                                :title (str (name region) " " (name layer)))
                         ;; another update when receive server data
                         (let [x (<! (fetch-transitions-data @state sel))]
                           (swap! plot-data assoc
                                  :sdr-transitions x)))
                       ;; same layer, just update matching sdrs
                       (swap! plot-data assoc
                              :matching-sdrs matching-sdrs))))))
    (fn transitions-plot []
      [canvas
       {}
       300
       800
       [plot-data]
       (fn [ctx]
         (draw-transitions-plot! ctx @plot-data))
       nil]
      )))
