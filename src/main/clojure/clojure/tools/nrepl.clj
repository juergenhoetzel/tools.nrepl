;   Copyright (c) Rich Hickey. All rights reserved.
;   The use and distribution terms for this software are covered by the
;   Eclipse Public License 1.0 (http://opensource.org/licenses/eclipse-1.0.php)
;   which can be found in the file epl-v10.html at the root of this distribution.
;   By using this software in any fashion, you are agreeing to be bound by
;   the terms of this license.
;   You must not remove this notice, or any other, from this software.

(ns #^{:doc ""
       :author "Chas Emerick"}
  clojure.tools.nrepl
  (:require clojure.main
    clojure.stacktrace
    clojure.tools.nrepl.helpers)
  (:import (java.net ServerSocket)
    clojure.lang.LineNumberingPushbackReader
    java.lang.ref.WeakReference
    (java.util Collections Map WeakHashMap)
    (java.io Reader InputStreamReader BufferedReader PushbackReader StringReader
      Writer OutputStreamWriter BufferedWriter PrintWriter StringWriter
      IOException)
    (java.util.concurrent Callable Future ExecutorService Executors LinkedBlockingQueue
      TimeUnit ThreadFactory
      CancellationException ExecutionException TimeoutException)))

(def *print-detail-on-error* false)
(def *pretty-print* false)

(def pprint prn)
(def pretty-print? (constantly false))
(def pretty-print-available? (constantly false))

(defn- configure-pprinting
  []
  (when (try
          (require '[clojure.pprint :as pprint]) ; clojure 1.2.0+
          true
          (catch Exception e
            ; clojure 1.0.0+ w/ contrib
            (try
              (require '[clojure.contrib.pprint :as pprint])
              true
              (catch Exception e))))
    ; clojure 1.1.0 requires this eval, throws exception not finding pprint ns
    ; I think 1.1.0 was resolving vars in the reader instead of the compiler?
    (alter-var-root #'pretty-print-available? (constantly (constantly true)))
    (alter-var-root #'pretty-print? (constantly (eval '(fn pretty-print? [] (and *pretty-print* pprint/*print-pretty*)))))
    (alter-var-root #'pprint (constantly (eval 'pprint/pprint)))
    true))

(configure-pprinting)

(def #^{:dynamic true
        :doc "Function that is used to print REPL exceptions when *print-detail-on-error* is true.
              Defaults to clojure.stacktrace/print-cause-trace."}
  *print-error-detail*)

(def #^ExecutorService executor (Executors/newCachedThreadPool
                                  (proxy [ThreadFactory] []
                                    (newThread [#^Runnable r]
                                      (doto (Thread. r)
                                        (.setDaemon true))))))

(def #^{:private true
        :doc "A map whose values are the Futures associated with client-requested evaluations,
              keyed by the evaluations' messages' IDs."}
       repl-futures (atom {}))

(def #^{:private true
        :doc "A map of session-ids -> client-state-atoms.  Sessions are not retained
              except by client request."}
  retained-sessions (atom {}))

(defn get-all-msg-ids
  []
  (keys @repl-futures))

(defn interrupt
  [msg-id]
  (when-let [{:keys [#^Future future interrupt-atom]} (@repl-futures msg-id)]
    (reset! interrupt-atom true)
    (.cancel future true)))

(defn- submit
  [#^Callable function]
  (.submit executor function))

; here only because the one in clojure.main is private
(defn- root-cause
  [#^Throwable throwable]
  (loop [cause throwable]
    (if-let [cause (.getCause cause)]
      (recur cause)
      cause)))

(defn- submit-looping
  ([function]
    (submit-looping function (fn [#^java.lang.Throwable cause]
                               (when-not (or (instance? IOException cause)
                                           (instance? java.lang.InterruptedException cause)
                                           (instance? java.nio.channels.ClosedByInterruptException cause))
                                 (pr-str "submit-looping: exception occured: " cause)))))
  ([function ex-fn]
    (submit (fn []
              (try
                (function)
                (recur)
                (catch Exception ex
                  (ex-fn (root-cause ex))))))))

(def version
  (when-let [in (-> submit class (.getResourceAsStream "/clojure/tools/nrepl/version.txt"))]
    (let [reader (-> in (InputStreamReader. "UTF-8") BufferedReader.)
          string (.readLine reader)]
      (.close reader)
      (->> (re-find #"(\d+)\.(\d+)\.(\d+)-?(.*)" string)
        rest
        (zipmap [:major :minor :incremental :qualifier])))))

;See the README for message format
;
;Not simply printing and reading maps because the client
;may not be clojure: e.g. whatever vimclojure might use to
;write/parse messages, a python/ruby/whatever client, etc.
(defn- write-message
  "Writes the given message to the writer. Returns the :id of the message."
  [#^Writer out msg]
  (locking out
    (binding [*out* out
              *print-readably* true]
      (prn (count msg))
      (doseq [[k v] msg]
        (prn (if (string? k) k (name k)))
        (prn v))
      (flush)))
  (:id msg))

(defn- read-message
  "Returns the next message from the given PushbackReader."
  [#^PushbackReader in]
  (locking in
    (binding [*in* in]
      (let [msg-size (read)]
        (->> (repeatedly read)
          (take (* 2 msg-size))
          (partition 2)
          (map #(vector (-> % first keyword) (second %)))
          (into {}))))))

(defn- init-client-state
  "Returns a map containing the 'baseline' client state of the current thread; everything
   that with-bindings binds, except for the prior result values, *e, and *ns*."
  []
  {:warn-on-reflection *warn-on-reflection*, :math-context *math-context*,
   :print-meta *print-meta*, :print-length *print-length*,
   :print-level *print-level*, :compile-path *compile-path*
   :command-line-args *command-line-args*
   :ns (create-ns 'user)
   :print-detail-on-error *print-detail-on-error*
   :pretty-print *pretty-print*
   :print-error-detail clojure.stacktrace/print-cause-trace})

(defmacro #^{:private true} set!-many
  [& body]
  (let [pairs (partition 2 body)]
    `(do ~@(for [[var value] pairs] (list 'set! var value)))))

(defn- create-repl-out
  [stream-key write-response]
  (let [sb (ref (StringBuilder.))]
    (PrintWriter. (proxy [Writer] []
                    (close []
                      (.flush this))
                    (write [& [x off len]]
                      (dosync
                        (cond
                          (number? x) (alter sb #(.append #^StringBuilder % (char x)))
                          (not off) (alter sb #(.append #^StringBuilder % x))
                          off (alter sb #(.append #^StringBuilder % x off len)))))
                    (flush []
                      ; would use (.setLength % 0) here, but clojure 1.1 fails that with
                      ; => Can't call public method of non-public class: public void java.lang.AbstractStringBuilder.setLength
                      (let [buffer (dosync (let [buffer @sb]
                                             (ref-set sb (StringBuilder.))
                                             buffer))]
                        (when (pos? (.length #^StringBuilder buffer))
                          (write-response stream-key (str buffer)))))))))

(defn- create-response
  [current-session & options]
  (assoc (apply hash-map options)
    :ns (-> @@current-session :ns ns-name str)))

(defn retain-session!
  "Retains the current repl session, returning the opaque string ID it
   is associated with.  This only needs to be done once per session to
   maintain its retention.  Other connections must specify a session-id
   in order to use the corresponding session.

   Please use release-session when you're done with one, or it'll never
   get GC'd.  This is an implementation detail; future versions of nrepl
   may institute some kind of reasonable session expiration policy."
  [client-state-atom]
  (let [session-id (or (:session-id @client-state-atom)
                     (str (java.util.UUID/randomUUID)))]
    (swap! client-state-atom assoc :session-id session-id)
    (swap! retained-sessions assoc session-id client-state-atom)
    session-id))

(defn release-session!
  "Releases the current session, indicating that it will not be requested
   again.  Returns true iff the session had been previously retained."
  [client-state-atom]
  (when-let [session-id (:session-id @client-state-atom)]
    (swap! retained-sessions dissoc session-id)
    true))

(defn- apply-session!
  [client-state]
  (let [{:keys [value-3 value-2 value-1 last-exception ns warn-on-reflection
                math-context print-meta print-length print-level compile-path
                command-line-args print-detail-on-error pretty-print
                print-error-detail]} client-state]
    (in-ns (ns-name ns))
    (set!-many
      *3 value-3
      *2 value-2
      *1 value-1
      *e last-exception
      *print-detail-on-error* print-detail-on-error
      *print-error-detail* print-error-detail
      *pretty-print* pretty-print
      *warn-on-reflection* warn-on-reflection
      *math-context* math-context
      *print-meta* print-meta
      *print-length* print-length
      *print-level* print-level
      *compile-path* compile-path
      *command-line-args* command-line-args)))

(defn- handle-request
  [client-state-atom write-response {:keys [code in interrupt-atom ns] :or {in ""} :as msg}]
  (let [code-reader (LineNumberingPushbackReader. (StringReader. code))
        out (create-repl-out :out write-response)
        err (create-repl-out :err write-response)]
    (binding [*in* (LineNumberingPushbackReader. (StringReader. in))
              *out* out
              *err* err
              *print-detail-on-error* *print-detail-on-error*
              *pretty-print* *pretty-print*
              *print-error-detail* clojure.stacktrace/print-cause-trace
              release-session! (partial release-session! client-state-atom)
              retain-session! (partial retain-session! client-state-atom)]
      (try
        (clojure.main/repl
          :init (partial apply-session! (if ns
                                          (assoc @client-state-atom :ns (symbol ns))
                                          @client-state-atom))
          :read (fn [prompt exit] (read code-reader false exit))
          :caught (fn [e]
                    (if @interrupt-atom ; we're interrupted, bugger out ASAP
                      (throw e)
                      (let [repl-exception (clojure.main/repl-exception e)]
                        (swap! client-state-atom assoc :last-exception e)
                        (write-response :status "error")
                        (binding [*out* *err*]
                          (if *print-detail-on-error*
                            (*print-error-detail* repl-exception)
                            (prn repl-exception))
                          (flush)))))
          :prompt (fn [])
          :need-prompt (constantly false)
          :print (fn [value]
                   (swap! client-state-atom assoc
                     :value-3 *2
                     :value-2 *1
                     :value-1 value
                     :ns *ns*
                     :print-detail-on-error *print-detail-on-error*
                     :print-error-detail *print-error-detail*
                     :pretty-print *pretty-print*)
                   (write-response :value (with-out-str
                                            (if (pretty-print?)
                                              (pprint value)
                                              (prn value))))))
        (finally (.flush *out*) (.flush *err*))))))

(def #^{:private true
        :doc "Currently one minute; this can't just be Long/MAX_VALUE, or we'll inevitably
              foul up the executor's threadpool with hopelessly-blocked threads.
              This can be overridden on a per-request basis by the client."}
       default-timeout (* 1000 60))

(defn- handle-response
  [#^Future future
   {:keys [id timeout interrupt-atom] :or {timeout default-timeout}}
   write-response]
  (try
    (.get future timeout TimeUnit/MILLISECONDS)
    (write-response :status "done")
    (catch CancellationException e
      (write-response :status "interrupted"))
    (catch TimeoutException e
      (write-response :status "timeout")
      (interrupt id))
    (catch ExecutionException e
      ; clojure.main.repl catches all Throwables, so this most often happens when
      ; attempting to send a :done message to a client that's already disconnected after
      ; getting their value(s), etc
      (when-not (or @interrupt-atom
                  (instance? java.net.SocketException (root-cause e)))
        (.printStackTrace e)
        (write-response :status "server-failure"
          :error "ExecutionException; this is probably an nREPL bug.")))
    (catch InterruptedException e
      ; only happens if the thread running handle-response is interrupted
      ; *should* be never
      (.printStackTrace e)
      (write-response :status "server-failure"
        :error "handle-response interrupted; this is probably an nREPL bug"))))
  
(defn- message-dispatch
  [current-session read-message write-response]
  ; TODO ouch, need some error handling here :-/
  (let [interrupt-atom (atom false)
        {:keys [id code session-id] :as msg} (assoc (read-message) :interrupt-atom interrupt-atom)
        write-response (partial write-response :id id)]
    (when-let [requested-session (and session-id (@retained-sessions session-id))]
      (reset! current-session requested-session))
    (if-not code
      (write-response :status "error" :error "Received message with no code.")
      (submit (fn []
                (try
                  (let [interruptable-write-response #(when-not @interrupt-atom
                                                        (apply write-response %&))
                        future (submit #(#'handle-request @current-session interruptable-write-response msg))]
                    (swap! repl-futures assoc id {:future future
                                                  :interrupt-atom interrupt-atom})
                    (handle-response future msg write-response))
                  (finally
                    (swap! repl-futures dissoc id))))))))

(defn- configure-streams
  [#^java.net.Socket sock]
  [(-> sock .getInputStream (InputStreamReader. "UTF-8") BufferedReader. PushbackReader.)
   (-> sock .getOutputStream (OutputStreamWriter. "UTF-8") BufferedWriter.)])

(defn- accept-connection
  [#^ServerSocket ss]
  (let [sock (.accept ss)
        [in out] (configure-streams sock)
        current-session (atom (atom (init-client-state)))]
    (submit-looping (partial #'message-dispatch
                      current-session
                      (partial read-message in)
                      (comp (partial write-message out)
                        (partial create-response current-session))))))

(defn- client-message
  "Returns a new message containing
   at minimum the provided code string and a generated unique id,
   along with any other options specified in the kwargs."
  [code & options]
  (assoc (apply hash-map options)
    :id (str (java.util.UUID/randomUUID))
    :code (str code "\n")))

(defn read-response-value
  "Returns the provided response message, replacing its :value string with
   the result of (read)ing it.  Returns the message unchanged if the :value
   slot is empty."
  [response-message]
  (if-let [value (:value response-message)]
    (try
      (assoc response-message :value (read-string value))
      (catch Exception e
        (throw (IllegalStateException. (str "Could not read response value: " (.trim value)) e))))    
    response-message))

(defn- send-client-message
  [#^Map response-promises out & message-args]
  (let [outgoing-msg (apply client-message message-args)
        q (LinkedBlockingQueue.)
        msg (assoc outgoing-msg ::response-queue q)]
    (.put response-promises (:id msg) (WeakReference. msg))
    (write-message out outgoing-msg)
    (fn response
      ([] (response default-timeout))
      ([x]
        (cond
          (number? x) (.poll q x TimeUnit/MILLISECONDS)
          (= :interrupt x) ((send-client-message
                              response-promises
                              out
                              (format "(clojure.tools.nrepl/interrupt \"%s\")" (:id msg))))
          :else (throw (IllegalArgumentException. (str "Invalid argument to REPL response fn: " x))))))))

(defn response-seq
  "Returns a lazy seq of the responses available through repeated invocations
   of the provided response function.  An optional timeout value can be provided,
   which will be passed along to the response function on each invocation."
  ([response-fn]
    (response-seq response-fn default-timeout))
  ([response-fn timeout]
    (lazy-seq
      (when-let [response (response-fn timeout)]
        (cons response
          (when-not (#{"done" "timeout" "interrupted"} (:status response))
            (response-seq response-fn timeout)))))))

(defn- conj*
  [coll coll? empty v]
  (conj
    (if coll
      (if (coll? coll)
        coll
        (conj empty coll))
      empty)
    v))

(defn combine-responses
  "Combines the provided response messages into a single response map.
   Typical usage being:

       (combine-responses (repl-response-seq ((:send connection) \"(some-expression)\")))

   Certain message slots are combined in special ways:

     - only the second :ns (last in a reduction) is retained
     - :value is accumulated into an ordered collection
     - :status is accumulated into a set
     - string values (associated with e.g. :out and :err) are concatenated"
  [responses]
  (let [r (reduce
            (fn [m [k v]]
              (cond
                (#{:id :ns} k) (assoc m k v)
                (= k :value) (update-in m [k] conj* vector? [] v)
                (= k :status) (update-in m [k] conj* set? #{} v)
                (string? v) (update-in m [k] #(str % v))
                :else (assoc m k v)))            
            {} (mapcat seq responses))
        v (:value r)]
    (if (and v (not (vector? v)))
      (assoc r :value [v])
      r)))

(defn- response-promises-map
  "Here only so we can force a connection to use a given map in tests
   to ensure that messages/response queues are being released
   in conjunction with their associated response fns."
  []
  (Collections/synchronizedMap (WeakHashMap.)))

(defn response-values
  [response-fn]
  (->> response-fn
    response-seq
    (map read-response-value)
    combine-responses
    :value))

(defmacro send-with
  "Sends the body of code using the provided connection (literally! No
   interpolation/quasiquoting of locals or other references is performed),
   returning a REPL response function."
  [connection & body]
  `(let [send# (or (:send ~connection) ~connection)
         code# (apply str (map pr-str (quote [~@body])))]
     (send# code#)))

(defmacro values-with
  [connection & body]
  `(response-values (send-with ~connection ~@body)))

(defn connect
  "Connects to a hosted REPL at the given host (defaults to localhost) and port,
   returning a map containing two functions:

   - send: a function that takes at least one argument (a code string
           to be evaluated) and optional kwargs:
           :timeout - number in milliseconds specifying the maximum runtime of
                      accompanying code (default: 60000, one minute)
           (send ...) returns a response function, described below.
   - close: no-arg function that closes the underlying socket

   Note that the connection/map object also implements java.io.Closeable,
   and is therefore usable with with-open.

   Response functions, returned from invocations of (send ...), accept zero or
   one argument. The one-arg arity accepts either:
       - a number of milliseconds, which is the maximum time that the invocation will block
         before returning the next response message.  If the timeout is exceeded, nil
         is returned.  Multiple response messages are expected for each sent request; a
         response message with a :status of \"done\" indicates that the associated request
         has been fully processed, and that no further response messages should be expected.
         See response-seq and combine-responses for some utilities for consuming message
         responses.
       - the :interrupt keyword. This sends an interrupt message for the request
         associated with the response function, and blocks for default-timeout milliseconds
         for confirmation of the interrupt.
   
   The 0-arg response function arity is the same as invoking (receive-fn default-timeout)."
  ([port] (connect nil port))
  ([#^String host #^Integer port]
    (let [sock (java.net.Socket. (or host "localhost") port)
          [in out] (configure-streams sock)
          ; Map<message-id, WeakReference<client-message>>
          ; this works as an "expiration" mechanism because the response fn
          ; is the only thing that closes over the client-message, which is
          ; where the message-id is sourced
          response-promises (response-promises-map)]
      (future (try
                (loop []
                  (let [response (read-message in)
                        #^WeakReference msg-ref (get response-promises (:id response))]
                    (when-let [#^LinkedBlockingQueue q (and msg-ref (-> msg-ref .get ::response-queue))]
                      (.put q response)))
                  (when-not (.isClosed sock) (recur)))
                (catch Throwable t
                  (let [root (root-cause t)]
                    (when-not (and (instance? java.net.SocketException root)
                                (.isClosed sock))
                      ; TODO need to get this pushed into an atom so clients can see what's gone sideways
                      (.printStackTrace t)
                      (throw t))))))
      (proxy [clojure.lang.PersistentArrayMap java.io.Closeable]
        [(into-array Object [:send (partial send-client-message response-promises out)
                             :close #(.close sock)])]
        (close [] (.close sock))))))

; could be a lot fancier, but it'll do for now
(def #^{:private true} ack-port-promise (atom nil))

(defn reset-ack-port!
  []
  (reset! ack-port-promise (promise))
  ; save people the misery of ever trying to deref the empty promise in their REPL
  nil)

(defn wait-for-ack
  [timeout]
  (let [#^Future f (future @@ack-port-promise)]
    (try
      (.get f timeout TimeUnit/MILLISECONDS)
      (catch TimeoutException e))))

(defn- send-ack
  [my-port ack-port]
  (let [connection (connect "localhost" ack-port)]
    (((:send connection) (format "(deliver @@#'clojure.tools.nrepl/ack-port-promise %d)" my-port)))))

(defn start-server
  ([] (start-server 0))
  ([port] (start-server port 0))
  ([port ack-port]
    (let [ss (ServerSocket. port)
          accept-future (submit-looping (partial accept-connection ss))]
      [ss accept-future (when (pos? ack-port)
                          (send-ack (.getLocalPort ss) ack-port))])))

;; TODO
;; - core
;;   - ensure init-client-state includes all defaults set by main/with-bindings (e.g. *compile-path* is missing)
;;   - add support for clojure 1.3.0 (var changes being the big issue there)
;;   - include :ns in responses only alongside :value and [:status "done"]
;;   - proper error handling on the receive loop
;;   - make write-response a send-off to avoid blocking in the REP loop.
;;   - bind out-of-band message options for evaluated code to access?
;; - tools
;;   - add ClojureQL-style quasiquoting to send-with
;; - streams
;;   - multiplex new *out*'s to System/out (or things like clojure.test/*test-out* will just disappear into the ether)
;;   - optionally multiplex System/out and System/err
;;   - optionally join multiplexed S/out and S/err, receive :stdout, :stderr msgs
;; - protocols and transport
;;   - dependency-free websockets adapter (should be able to run on the same port!)
;;   - STOMP when dep-free client and broker impls are available
;; - cmdline
;;   - support for connecting to a server
;;   - optionally running other clojure script(s)/java mains prior to starting/connecting to a server
