(ns google-apps-clj.google-drive
  "A library for connecting to Google Drive through the Drive API"
  (:require [clojure.core.typed :as t]
            [clojure.core.typed.unsafe :as tu]
            [clojure.edn :as edn :only [read-string]]
            [clojure.java.io :as io :only [as-url file resource]]
            [clojure.string :as string]
            [google-apps-clj.credentials :as cred])
  (:import (com.google.api.client.googleapis.batch BatchRequest
                                                   BatchCallback)
           (com.google.api.client.googleapis.json GoogleJsonError
                                                  GoogleJsonError$ErrorInfo
                                                  GoogleJsonErrorContainer
                                                  GoogleJsonResponseException)
           (com.google.api.client.http FileContent
                                       InputStreamContent
                                       GenericUrl)
           (com.google.api.client.util GenericData)
           (com.google.api.services.drive Drive
                                          Drive$Builder
                                          Drive$Files$Delete
                                          Drive$Files$Get
                                          Drive$Files$Insert
                                          Drive$Files$List
                                          Drive$Files$Update
                                          Drive$Permissions$Delete
                                          Drive$Permissions$Insert
                                          Drive$Permissions$List
                                          Drive$Permissions$Update
                                          DriveRequest
                                          DriveScopes)
           (com.google.api.services.drive.model File
                                                File$Labels
                                                FileList
                                                ParentReference
                                                Permission
                                                PermissionId
                                                PermissionList
                                                Property
                                                PropertyList
                                                User)
           (java.io InputStream)))

(t/ann ^:no-check clojure.core/slurp [java.io.InputStream -> String])
(t/ann ^:no-check clojure.java.io/input-stream [t/Any -> java.io.InputStream])

(t/non-nil-return com.google.api.services.drive.Drive/files :all)
(t/non-nil-return com.google.api.services.drive.Drive$Files/delete :all)
(t/non-nil-return com.google.api.services.drive.Drive$Files/get :all)
(t/non-nil-return com.google.api.services.drive.Drive$Files/insert :all)
(t/non-nil-return com.google.api.services.drive.Drive$Files/list :all)
(t/non-nil-return com.google.api.services.drive.Drive$Files/update :all)

(t/non-nil-return com.google.api.services.drive.Drive/permissions :all)
(t/non-nil-return com.google.api.services.drive.Drive$Permissions/delete :all)
(t/non-nil-return com.google.api.services.drive.Drive$Permissions/insert :all)
(t/non-nil-return com.google.api.services.drive.Drive$Permissions/list :all)
(t/non-nil-return com.google.api.services.drive.Drive$Permissions/update :all)

(t/ann build-drive-service [cred/GoogleCtx -> Drive])
(defn ^Drive build-drive-service
  "Given a google-ctx configuration map, builds a Drive service using
   credentials coming from the OAuth2.0 credential setup inside google-ctx"
  [google-ctx]
  (let [drive-builder (->> google-ctx
                           cred/build-credential
                           (Drive$Builder. cred/http-transport cred/json-factory))]
    (cast Drive (doto (.build drive-builder)
                  assert))))

;;; Experimental fns operating on query data structures, with support for batching

(t/defalias FileId t/Str)

;; TODO union type of anything clojure.java.io/input-stream allows
(t/defalias FileUploadContent t/Any)

(t/defalias Fields (t/Seq (t/U t/Keyword t/Str)))

(t/defalias PermissionIdentifier t/Str)

(t/defalias Role (t/U ':owner ':writer ':reader))

(t/defalias PermissionType (t/U ':user ':group ':domain ':anyone))

(t/defalias FileDeleteQuery
  (t/HMap :mandatory {:model ':files
                      :action ':delete
                      :file-id FileId}
          :complete? true))

(t/defalias FileGetQuery
  (t/HMap :mandatory {:model ':files
                      :action ':get
                      :file-id FileId}
          :optional {:fields Fields}
          :complete? true))

(t/defalias FileInsertQuery
  (t/HMap :mandatory {:model ':files
                      :action ':insert
                      :description t/Str
                      :mime-type t/Str
                      :title t/Str}
          :optional {:fields Fields
                     :parent-ids (t/Seq FileId)
                     :writers-can-share? t/Bool
                     :content FileUploadContent
                     :size Long} ; TODO non-negative integer
          :complete? true))

(t/defalias FileListQuery
  (t/HMap :mandatory {:model ':files
                      :action ':list}
          :optional {:query t/Str
                     :fields Fields}
          :complete? true))

(t/defalias FileUpdateQuery
  (t/HMap :mandatory {:model ':files
                      :action ':update
                      :file-id FileId}
          :optional {:fields Fields
                     :parent-ids (t/Seq FileId)
                     :writers-can-share? t/Bool
                     :content FileContent
                     :size t/Int}
          :complete? true))

(t/defalias PermissionDeleteQuery
  (t/HMap :mandatory {:model ':permissions
                      :action ':delete
                      :file-id FileId
                      :permission-id PermissionIdentifier}
          :complete? true))

(t/defalias PermissionInsertQuery
  (t/HMap :mandatory {:model ':permissions
                      :action ':insert
                      :file-id FileId
                      :role Role
                      :type PermissionType
                      :value t/Str}
          :optional {:with-link? t/Bool
                     :fields Fields}
          :complete? true))

(t/defalias PermissionListQuery
  (t/HMap :mandatory {:model ':permissions
                      :action ':list
                      :file-id FileId}
          :optional {:fields Fields}
          :complete? true))

(t/defalias PermissionUpdateQuery
  (t/HMap :mandatory {:model ':permissions
                      :action ':update
                      :file-id FileId
                      :permission-id PermissionIdentifier
                      :role Role}
          :optional {:fields Fields
                     :with-link? t/Bool
                     :transferOwnership? t/Bool}
          :complete? true))

(t/defalias Query
  (t/U FileDeleteQuery
       FileGetQuery
       FileInsertQuery
       FileListQuery
       FileUpdateQuery
       PermissionDeleteQuery
       PermissionInsertQuery
       PermissionListQuery
       PermissionUpdateQuery))

(t/defalias Request
  (t/U Drive$Files$Delete
       Drive$Files$Get
       Drive$Files$Insert
       Drive$Files$List
       Drive$Files$Update
       Drive$Permissions$Delete
       Drive$Permissions$Insert
       Drive$Permissions$List
       Drive$Permissions$Update))

(defmacro cond-doto
  [x & forms]
  (assert (even? (count forms)))
  (let [gx (gensym)]
    `(let [~gx ~x]
       ~@(map (fn [[test expr]]
                (if (seq? expr)
                  `(when ~test (~(first expr) ~gx ~@(next expr)))
                  `(when ~test (~expr ~gx))))
              (partition 2 forms))
       ~gx)))

(t/ann build-file [(t/U FileInsertQuery FileUpdateQuery) -> File])
(defn- ^File build-file
  [query]
  (let [{:keys [description mime-type parent-ids title writers-can-share?]} query
        parents (when (seq parent-ids)
                  (map (t/fn [id :- FileId]
                         (doto (ParentReference.)
                           (.setId id)))
                       parent-ids))]
    (cond-doto (File.)
      description (.setDescription description)
      mime-type (.setMimeType mime-type)
      parents (.setParents parents)
      title (.setTitle title)
      (not (nil? writers-can-share?)) (.setWritersCanShare writers-can-share?))))

(t/ann build-stream [(t/U FileInsertQuery FileUpdateQuery) ->
                     (t/Option InputStreamContent)])
(defn- ^InputStreamContent build-stream
  [query]
  (let [{:keys [content mime-type size]} query]
    (when (and content mime-type)
      (cond-doto (InputStreamContent. ^String mime-type (io/input-stream content))
        size (.setLength ^Long size)))))

(t/ann build-request [cred/GoogleCtx Query -> Request])
(defn- ^DriveRequest build-request
  "Converts a query into a stateful request object executable in the
   given google context. Queries are maps with the following required
   fields:

   :model - :files, :permissions
   :action - :list, :get, :update, :insert, :delete

   Other fields may be given, and may be required by the action and model.
   These may include:

   :fields - a seq of keywords specifying the object projection
   :query - used to constrain a list of files
   :file-id - specifies the file for file-specific models and actions"
  [google-ctx query]
  (let [drive (build-drive-service google-ctx)
        {:keys [model action fields]} query
        ;; TODO more rigorous support for nesting, e.g. permissions(role,type)
        fields (when (seq fields) (string/join "," (map name fields)))
        items? (= :list action)
        fields-seq (cond-> []
                     (and items? (= model :files))
                     (conj "nextPageToken")
                     (and items? fields)
                     (conj (format "items(%s)" fields))
                     (and items? (not fields))
                     (conj "items")
                     (and (not items?) fields)
                     (conj fields))
        fields (when (seq fields-seq) (string/join "," fields-seq))]
    (case model
      :files
      (case action
        :delete
        (let [{:keys [file-id]} query]
          (.delete (.files drive) file-id))
        :list
        (let [{:keys [query]} query
              request (cond-doto (.list (.files drive))
                        query (.setQ query))]
          (cond-doto ^DriveRequest request
            fields (.setFields fields)))
        :get
        (let [{:keys [file-id]} query
              request (.get (.files drive) file-id)]
          (cond-doto ^DriveRequest request
            fields (.setFields fields)))
        :update
        (let [{:keys [file-id]} query
              file (build-file query)
              stream (build-stream query)
              request (if stream
                        (.update (.files drive) file-id file stream)
                        (.update (.files drive) file-id file))]
          (cond-doto ^DriveRequest request
            fields (.setFields fields)))
        :insert
        (let [file (build-file query)
              stream (build-stream query)
              request (if stream
                        (doto (.insert (.files drive) file stream)
                          (.setConvert true))
                        (.insert (.files drive) file))]
          (cond-doto ^DriveRequest request
            fields (.setFields fields))))
      :permissions
      (case action
        :list
        (let [{:keys [file-id]} query
              request (.list (.permissions drive) file-id)]
          (cond-doto ^DriveRequest request
            fields (.setFields fields)))
        :insert
        (let [{:keys [file-id value role type with-link?]} query
              permission (doto (Permission.)
                             (.setRole (name role))
                             (.setType (name type))
                             (.setValue value)
                             (cond-doto (not (nil? with-link?))
                               (.setWithLink with-link?)))
              request (doto (.insert (.permissions drive) file-id permission)
                        (.setSendNotificationEmails false))]
          (cond-doto ^DriveRequest request
            fields (.setFields fields)))
        :update
        (let [{:keys [file-id permission-id role transfer-ownership?]} query
              permission (doto (Permission.)
                           (.setRole (name role)))
              request (cond-doto (.update (.permissions drive)
                                          file-id permission-id permission)
                        (not (nil? transfer-ownership?))
                        (.setTransferOwnership transfer-ownership?))]
          (cond-doto ^DriveRequest request
            fields (.setFields fields)))
        :delete
        (let [{:keys [file-id permission-id]} query]
          (.delete (.permissions drive) file-id permission-id))))))

(t/defprotocol Requestable
  (response-data
   [request response :- (t/Option java.util.Map)] :- (t/Option (t/U java.util.Map java.util.List))
   "Extracts the good bit from the response")
  (next-page!
   [request response :- (t/Option java.util.Map)] :- (t/Option Requestable)
   "Mutates the request to retrieve the next page of results if supported and
    present"))

(extend-protocol Requestable
  Drive$Files$Delete
  (next-page! [request response])
  (response-data [request response]
    response)

  Drive$Files$Get
  (next-page! [request response])
  (response-data [request response]
    response)

  Drive$Files$Insert
  (next-page! [request response])
  (response-data [request response]
    response)

  Drive$Files$List
  (next-page! [request ^FileList response]
    (when-let [page-token (.getNextPageToken response)]
      (.setPageToken request page-token)))
  (response-data [request ^FileList response]
    (.getItems response))

  Drive$Files$Update
  (next-page! [request response])
  (response-data [request response]
    response)

  Drive$Permissions$Delete
  (next-page! [request response])
  (response-data [request response]
    response)

  Drive$Permissions$Insert
  (next-page! [request response])
  (response-data [request response]
    response)

  Drive$Permissions$List
  (next-page! [request response])
  (response-data [request ^PermissionList response]
    (.getItems response))

  Drive$Permissions$Update
  (next-page! [request response])
  (response-data [request response]
    response))

;; TODO perhaps validate the codomain is a subset of the keyword domain
(t/ann camel->kebab [t/Str -> t/Str])
(defn- camel->kebab
  [camel]
  (let [accum (StringBuffer.)]
    (loop [[^Character c & cs] camel]
      (if (not c)
        (.toString accum)
        (let [c' (Character/toLowerCase c)]
          (when-not (= c' c)
            (.append accum \-))
          (.append accum c')
          (recur cs))))
    (.toString accum)))

(t/ann convert-bean [java.util.Map -> (t/HMap)])
(defn- convert-bean
  [bean]
  (->> (keys bean)
       (map (juxt (comp keyword camel->kebab) (partial get bean)))
       (into {})))

(defprotocol Response
  (convert-response
   [_]
   "Convert the google response object into a clojure form"))

(extend-protocol Response
  java.util.List
  (convert-response [l]
    (mapv convert-response l))
  com.google.api.client.util.ArrayMap
  (convert-response [m]
    m)
  com.google.api.client.json.GenericJson
  (convert-response [m]
    (->> (keys m)
         (map (fn [field]
                (let [value (convert-response (get m field))
                      field (if-not (true? value)
                              (-> field camel->kebab keyword)
                              (-> field camel->kebab (str "?") keyword))]
                  [field value])))
         (into {})))
  com.google.api.client.util.DateTime
  (convert-response [dt]
    ;; TODO convert to inst or jodatime
    dt)
  java.lang.String
  (convert-response [s]
    s)
  java.lang.Long
  (convert-response [l]
    l)
  java.lang.Boolean
  (convert-response [b]
    b)
  nil
  (convert-response [_]))

(t/ann rate-limit-exceeded? [GoogleJsonError -> t/Bool])
(defn- rate-limit-exceeded?
  [^GoogleJsonError error]
  (and (= 403 (.getCode error))
       (some (fn [^GoogleJsonError$ErrorInfo error]
               (let [reason (.getReason error)]
                 (case reason
                   "rateLimitExceeded" true
                   "userRateLimitExceeded" true
                   false)))
             (.getErrors error))))

;; TODO Can core.typed use a protocol as a type?
(t/ann execute-query! [cred/GoogleCtx Query -> t/Any])
(defn execute-query!
  "Executes the given query in the google context and returns the
   results converted into clojure forms. If the response is paginated,
   all results are fetched and concatenated into a vector."
  [google-ctx query]
  (let [request (build-request google-ctx query)
        results (atom nil)]
    ;; TODO the results could be a volatile in clojure 1.7
    (loop []
      (let [response (.execute request)
            data (convert-response (response-data request response))]
        (if (next-page! request response)
          (do
            (swap! results (fn [extant] (into (or extant []) data)))
            (recur))
          (swap! results (fn [extant]
                           (if extant
                             (into extant data)
                             data))))))
    @results))

;; TODO can seq or vec types declare their lengths?
(t/ann execute-batch! [cred/GoogleCtx (t/Seq Query) -> (t/Seq t/Any)])
(defn execute-batch!
  "Execute the given queries in a batch, returning their responses,
   converted into clojure forms, in the same order as the queries. If
   any queries in a batch yield paginated responses, another batch will
   be executed for all such queries, iteratively until all pages have
   been received, and the results concatenated into vectors as in
   execute!.

   Queries that yield error results due to rate limits are retried
   after sleeping up to 200ms. This sleep is cumulative for the batch.
   There is no limit on the number of rate limit retries. All other
   errors are given as GoogleJsonError objects in the responses."
  [google-ctx queries]
  ;; TODO partition queries into batches of 1000
  (let [requests (map (partial build-request google-ctx) queries)
        credential (cred/build-credential google-ctx)
        batch (BatchRequest. cred/http-transport credential)
        responses (atom (into [] (repeat (count requests) nil)))]
    (loop [requests (map-indexed vector requests)]
      (let [next-requests (atom {})]
        (doseq [[i ^DriveRequest request] requests]
          (.queue request batch GoogleJsonErrorContainer
                  (reify BatchCallback
                    (onSuccess [_ response headers]
                      (let [data (convert-response (response-data request response))]
                        (if (next-page! request response)
                          (do
                            (swap! next-requests assoc i request)
                            (swap! responses
                                   (fn [responses]
                                     (let [extant (nth responses i)
                                           response (into (or extant []) data)]
                                       (assoc responses i response)))))
                          (swap! responses
                                 (fn [responses]
                                   (let [extant (nth responses i)
                                         response (if extant
                                                    (into extant data)
                                                    data)]
                                     (assoc responses i response)))))))
                    (onFailure [_ container headers]
                      (let [error (.getError ^GoogleJsonErrorContainer container)]
                        (if (rate-limit-exceeded? error)
                          (do
                            (Thread/sleep (+ 100 (rand-int 100)))
                            (swap! next-requests assoc i request))
                          (swap! responses assoc i error)))))))
        (.execute batch)
        (let [next-requests @next-requests]
          (when (seq next-requests)
            (recur next-requests)))))
    @responses))

(t/ann execute! [cred/GoogleCtx (t/Seq Query) -> (t/Seq t/Any)])
(defn execute!
  "Executes the given queries in the most efficient way, returning their
   results in a seq of clojure forms."
  [google-ctx queries]
  (when (seq queries)
    (if (= 1 (count queries))
      [(execute-query! google-ctx (first queries))]
      (execute-batch! google-ctx queries))))

;;;; Commands and their helpers

(t/ann derive-type [t/Str -> PermissionType])
(defn- derive-type
  [^String principal]
  (cond (= "anyone" principal)
        :anyone
        (pos? (.indexOf principal "@"))
        :user ; This seems to work correctly for users and groups
        :else
        :domain))

(defn- derive-principal
  [permission]
  (let [{:keys [type email-address domain]} permission]
    (case type
      "anyone" "anyone"
      "domain" domain
      "group" email-address
      "user" email-address)))

(defn get-permissions!
  "Returns the permissions granted on the given file, filtered for those
   explicitly granted to the principal if given"
  ([google-ctx file-id]
   (get-permissions! google-ctx file-id false))
  ([google-ctx file-id principal]
   (let [list-query {:model :permissions
                     :action :list
                     :file-id file-id
                     :fields [:id :role :withLink :type :domain :emailAddress]}
         permissions (execute-query! google-ctx list-query)]
     (cond->> permissions
       principal (filter (fn [permission]
                           (condp = (derive-type principal)
                             :user
                             (and (= principal (:email-address permission))
                                  (#{"user" "group"} (:type permission)))
                             :domain
                             (and (= principal (:domain permission))
                                  (= "domain" (:type permission)))
                             :anyone
                             (and (= "anyone" (:type permission))))))))))

(defn summarize-permissions
  "Returns a map of the sets of principals in the given permissions grouped by
   role"
  [permissions]
  (reduce (fn [accum permission]
            (let [{:keys [role]} permission]
              (update-in accum [role] (fnil conj #{})
                         (derive-principal permission))))
          {}
          permissions))

(defn assign!
  "Authorize the principal with the role on the given file. The principal will
   not be able to discover the file via google unless the searchable? field is
   true. The principal may be the literal \"anyone\", an email address of a
   user or google app group, or a google app domain.

   If the principal has any other permissions, they will be deleted. If the
   principal has permission for this authorization already, it will be left
   intact, otherwise a new permission will be inserted.

   This operation should be idempotent until the the permissions change by some
   other operation."
  [google-ctx file-id authorization]
  (let [{:keys [principal role searchable?]} authorization
        extant (get-permissions! google-ctx file-id principal)
        principal-id (atom nil) ; TODO this could be a volatile
        ids-to-delete (atom [])]
    ;; [principal withLink] seem to be a unique key within a file
    (doseq [permission extant]
      (if (and (= (name role) (:role permission))
               (case searchable?
                 true (true? (:with-link? permission))
                 false (nil? (:with-link? permission))))
        (reset! principal-id (:id permission))
        (swap! ids-to-delete conj (:id permission))))
    (let [deletes (map (fn [id] {:model :permissions
                                 :action :delete
                                 :file-id file-id
                                 :permission-id id})
                       @ids-to-delete)
          insert (when-not @principal-id
                   {:model :permissions
                    :action :insert
                    :file-id file-id
                    :value principal
                    :role role
                    :type (derive-type principal)
                    :with-link? searchable?
                    :fields [:id]})]
      (execute! google-ctx deletes)
      (when insert
        (execute-query! google-ctx insert)))
    nil))

(defn revoke!
  "Revoke all authorizations for the given principal on the given file. The
   principal may be the literal \"anyone\", an email address of a user or
   google app group, or a google app domain.

   This operation should be idempotent until the the permissions change by some
   other operation."
  [google-ctx file-id principal]
  (let [extant (get-permissions! google-ctx file-id principal)
        deletes (mapv (fn [permission]
                        {:model :permissions
                         :action :delete
                         :file-id file-id
                         :permission-id (:id permission)})
                      extant)]
    (execute! google-ctx deletes)
    nil))

(defn get-authorizations!
  [google-ctx file-id]
  (let [request {:model :permissions
                 :action :list
                 :file-id file-id
                 :fields [:emailAddress :type :role :domain]
                 }]))

(def folder-mime-type
  "application/vnd.google-apps.folder")

(t/ann create-folder [FileId t/Str -> FileInsertQuery])
(defn create-folder
  [parent-id title]
  {:model :files
   :action :insert
   :parent-ids [parent-id]
   :mime-type folder-mime-type
   :title title})

(defn create-folder!
  "Create a folder with the given title in the given parent folder"
  [google-ctx parent-id title]
  (execute-query! google-ctx (create-folder parent-id title)))

(t/ann move-file [FileId FileId -> FileUpdateQuery])
(defn move-file
  [folder-id file-id]
  {:model :files
   :action :update
   :parent-ids [folder-id]
   :file-id file-id})

(defn move-file!
  "Moves a file to a folder. This returns true if successful, false
   if forbidden, and raises otherwise."
  [google-ctx folder-id file-id]
  (try
    (execute-query! google-ctx (move-file folder-id file-id))
    true
    (catch GoogleJsonResponseException e
      (when (not= 400 (.getStatusCode e))
        (throw e))
      false)))

(t/ann upload-file [FileId t/Str t/Str t/Str t/Any -> FileInsertQuery])
(defn upload-file
  [folder-id title description mime-type content]
  {:model :files
   :action :insert
   :parent-ids [folder-id]
   :title title
   :description description
   :mime-type mime-type
   :content content})

(defn upload-file!
  "Uploads a file with the given title, description, type, and content into
   the given folder"
  [google-ctx folder-id title description mime-type content]
  (execute-query! google-ctx
                  (upload-file folder-id title description mime-type content)))

(t/ann delete-file [FileId -> FileDeleteQuery])
(defn delete-file
  [file-id]
  {:model :files
   :action :delete
   :file-id file-id})

(defn delete-file!
  "Permanently deletes the given file. If the file is a folder, this also
   deletes all of its descendents."
  [google-ctx file-id]
  (execute-query! google-ctx (delete-file file-id)))

(t/ann list-files [FileId -> FileListQuery])
(defn list-files
  [folder-id]
  {:model :files
   :action :list
   :query (format "'%s' in parents" folder-id)})

(defn list-files!
  [google-ctx folder-id]
  "Returns a seq of files in the given folder"
  (execute-query! google-ctx (list-files folder-id)))

(t/ann get-file [FileId -> FileGetQuery])
(defn get-file
  [file-id]
  {:model :files
   :action :get
   :file-id file-id})

(defn get-file!
  [google-ctx file-id]
  "Returns the metadata for the given file"
  (execute-query! google-ctx (get-file file-id)))

;; TODO core.typed should complain that not all Query types have :fields?
(t/ann with-fields [Query -> Query])
(defn with-fields
  [query fields]
  "Sets or adds to the set of fields returned by the given request"
  (update-in query [:fields] (fnil into #{}) fields))

;;; These vars probably belong elsewhere, e.g. a google-drive.repl ns

(defn all-files
  [google-ctx]
  (let [fields [:id :title :writersCanShare :mimeType
                "permissions(emailAddress,type,domain,role,withLink)"
                "owners(emailAddress)"
                "parents(id)"]
        query {:model :files
               :action :list
               :fields fields
               :query "trashed=false"}]
    (execute-query! google-ctx query)))

(defn parent-ids
  [file]
  (into #{} (map :id (:parents file))))

(defn folder?
  [file]
  (= folder-mime-type (:mime-type file)))

(defn resolve-file-id!
  [google-ctx path]
  (when (seq path)
    (loop [folder-id "root"
           [title & path'] path]
      (let [q (format "'%s' in parents and title = '%s'" folder-id title)
            query {:model :files
                   :action :list
                   :fields [:id]
                   :query q}
            results (execute-query! google-ctx query)
            total (count results)]
        (when (seq results)
          (when (seq (rest results))
            (let [msg (format "Can't resolve path %s, too many matches for %s"
                              (pr-str path) title)]
              (throw (IllegalStateException. msg))))
          (let [id (:id (first results))]
            (if (seq path')
              (recur id path')
              id)))))))

;; abandoned files belong to folders not in our corpus
;; orphaned files do not belong to any folders
;; index looks up a file by id
;; children looks up child files by parent id
(defn file-tree
  [files]
  (let [index (->> files
                   (map (juxt :id identity))
                   (into {}))]
    (reduce (fn [accum file]
              (let [parent-ids (parent-ids file)]
                (if (seq parent-ids)
                  (reduce (fn [accum parent-id]
                            (-> accum
                                (update-in [:children parent-id]
                                           (fnil conj []) file)
                                (cond-> (not (index parent-id))
                                  (update-in [:abandoned]
                                             (fnil conj []) file))))
                          accum
                          parent-ids)
                  (update-in accum [:orphans] (fnil conj []) file))))
            {:index index}
            files)))

(defn folder-seq
  [tree folder-id]
  (let [{:keys [index children]} tree]
    (tree-seq folder?
              (comp children :id)
              (index folder-id))))

(defn rfolder-tree!
  [creds folder-id]
  (let [fields [:id :title :writersCanShare :mimeType
                "permissions(emailAddress,type,domain,role,withLink)"
                "owners(emailAddress)"]
        list-files #(-> % list-files (with-fields fields))
        tree (atom {:children {}
                    :index {}})]
    (loop [folder-ids [folder-id]]
      (when (seq folder-ids)
        (let [batches (partition-all 100 (map list-files folder-ids))
              responses (mapcat (partial execute! creds) batches)]
          (doseq [[folder-id files] (map vector folder-ids responses)]
            (swap! tree (fn [tree]
                          (reduce (fn [accum file]
                                    (assoc-in accum [:index (:id file)] file))
                                  (assoc-in tree [:children folder-id] files)
                                  files))))
          (recur (map :id (filter folder? (apply concat responses)))))))
    @tree))

;;; Everything hereafter should probably be rewritten in terms of the above
;;; fns, though some response types will change if we do

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; File Management ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

#_(t/ann ^:no-check get-file-ids [cred/GoogleCtx -> (t/Map String String)])
#_(defn get-file-ids
  "Given a google-ctx configuration map, gets the file-id and title
   for every file under the users Drive as a map in the structure
   of {file-id file-title}"
  [google-ctx]
  (let [drive-service (build-drive-service google-ctx)
        drive-files (doto (.files ^Drive drive-service)
                      assert)
        files-list (doto (.list drive-files)
                     assert)
        all-files (doto (.getItems (.execute files-list))
                    assert)
        extract-id (fn [file]
                     (let [file-map (into {} file)]
                       {(get file-map "id") (get file-map "title")}))]
    (into {} (map extract-id all-files))))

#_(t/ann query-files [cred/GoogleCtx String -> (t/Vec File)])
#_(defn query-files
  "Runs the given query against the given context and returns the results
   as a vector of File objects"
  [google-ctx query]
  ;; The Drive object explicitly disclaims thread-safety, and the contracts
  ;; given by the execute response and items are unclear, so instead of
  ;; concatenating the items, we explicitly copy them into a vector.
  ;;
  ;; We eagerly realize the results to avoid the stack abuse given by the naive
  ;; lazy seq recursive concat approach, as well as to reduce the chance of
  ;; drive mutations affecting the results.
  (let [request (some-> (build-drive-service google-ctx)
                        .files
                        .list
                        (.setQ query))
        results (transient [])]
    request
    #_(loop []
        (let [response (.execute request)]
          (doseq [file (.getItems response)]
            (conj! results file))
          (when-let [page-token (.getNextPageToken response)]
            (.setPageToken request page-token)
            (recur))))
    #_(persistent! results)))

#_(t/ann get-files [cred/GoogleCtx File -> (t/Vec File)])
#_(defn get-files
  "Returns a seq of files in the given folder"
  [google-ctx folder]
  (query-files google-ctx
               (str "'" (.getId folder) "' in parents and trashed=false")))

#_(t/ann folder? [File -> Boolean])
#_(defn folder?
  "Returns true if the file is a folder"
  [file]
  (= "application/vnd.google-apps.folder" (.getMimeType file)))

#_(t/ann folder-seq [cred/GoogleCtx File -> (t/Seq File)])
#_(defn folder-seq
  "Returns a lazy seq of all files in the given folder, including itself, via a
   depth-first traversal"
  [google-ctx folder]
  (tree-seq folder? (partial get-files google-ctx) folder))

#_(t/ann get-root-files [cred/GoogleCtx -> (t/Vec File)])
#_(defn get-root-files
  "Given a google-ctx configuration map, gets a seq of files from the user's
   root folder"
  [google-ctx]
  (query-files google-ctx "'root' in parents and trashed=false"))

#_(t/ann get-file [cred/GoogleCtx String -> File])
#_(defn get-file
  "Given a google-ctx configuration map and the id of the desired
  file as a string, returns that file as a drive File object"
  [google-ctx file-id]
  (let [drive-service (build-drive-service google-ctx)
        drive-files (doto (.files ^Drive drive-service)
                      assert)
        get-file (doto (.get drive-files file-id)
                   assert)]
    (cast File (doto (.execute get-file)
                 assert))))

#_(t/ann upload-file [cred/GoogleCtx java.io.File String String String String -> File])
#_(defn upload-file
  "Given a google-ctx configuration map, a file to upload, an ID of
   the parent folder you wish to insert the file in, the title of the
   Drive file, the description of the Drive file, and the MIME type of
   the file, builds a Drive Service and inserts this file into Google
   Drive with permissions of the folder it's inserted into. The owner
   is whomever owns the Credentials used to make the Drive Service"
  [google-ctx file parent-folder-id file-title file-description media-type]
  (let [drive-service (build-drive-service google-ctx)
        parent-folder (doto (ParentReference.)
                        (.setId parent-folder-id))
        drive-file (doto (File.)
                     (.setTitle file-title)
                     (.setDescription file-description)
                     (.setMimeType media-type)
                     (.setParents (vector parent-folder)))
        media-content (FileContent. media-type file)
        drive-files (doto (.files ^Drive drive-service)
                     assert)
        drive-file (doto (.insert drive-files drive-file media-content)
                     assert
                     (.setConvert true))]
    (cast File (doto (.execute drive-file)
                 assert))))

#_(t/ann create-blank-file [cred/GoogleCtx String String String String -> File])
#_(defn create-blank-file
  "Given a google-ctx configuration map, an ID of the parent folder you
   wish to insert the file in, the title of the Drive file, the description
   of the Drive file, and the MIME type of the file(which will be converted
   into a google file type, builds a Drive Service and inserts a blank file
   into Google Drive with permissions of the folder it's inserted into. The
   owner is whomever owns the Credentials used to make the Drive Service"
  [google-ctx parent-folder-id file-title file-description media-type]
  (let [file (doto (java.io.File/createTempFile "temp" "temp")
               assert)]
    (upload-file google-ctx file parent-folder-id file-title file-description media-type)))

#_(t/ann download-file [cred/GoogleCtx String String -> String])
#_(defn download-file
  "Given a google-ctx configuration map, a file id to download,
   and a media type, download the drive file and then read it in
   and return the result of reading the file"
  [google-ctx file-id media-type]
  (let [drive-service (build-drive-service google-ctx)
        files (doto (.files ^Drive drive-service)
                assert)
        files-get (doto (.get files file-id)
                    assert)
        file (cast File (doto (.execute files-get)
                          assert))
        http-request (doto (.getRequestFactory ^Drive drive-service)
                       assert)
        export-link (doto (.getExportLinks ^File file)
                      assert)
        generic-url (GenericUrl. ^String (doto (cast String (get export-link media-type))
                                                         assert))
        get-request (doto (.buildGetRequest http-request generic-url)
                      assert)
        response (doto (.execute get-request)
                   assert)
        input-stream (doto (.getContent response)
                       assert)]
    (slurp input-stream)))

#_(t/ann delete-file [cred/GoogleCtx String -> File])
#_(defn delete-file
  "Given a google-ctx configuration map, and a file
   id to delete, moves that file to the trash"
  [google-ctx file-id]
  (let [drive-service (build-drive-service google-ctx)
        files (doto (.files ^Drive drive-service)
                assert)
        delete-request (doto (.trash files file-id)
                         assert)]
    (cast File (doto (.execute delete-request)
                 assert))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;; File Edits ;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

#_(t/ann update-file-title [cred/GoogleCtx String String -> File])
#_(defn update-file-title
  "Given a google-ctx configuration map, a file id, and a title,
   updates the title of the given file to the given title."
  [google-ctx file-id title]
  (let [drive-service (build-drive-service google-ctx)
        files (doto (.files ^Drive drive-service)
                assert)
        files-get (doto (.get files file-id)
                    assert)
        file (cast File (doto (.execute files-get)
                          assert))
        file (doto (.setTitle ^File file title)
               assert)
        update-request (doto (.update files file-id file)
                         assert)]
    (cast File (doto (.execute update-request)
                 assert))))

#_(t/ann update-file-description [cred/GoogleCtx String String -> File])
#_(defn update-file-description
  "Given a google-ctx configuration map, a file id, and a description,
   updates the description of the given file to the given description."
  [google-ctx file-id description]
  (let [drive-service (build-drive-service google-ctx)
        files (doto (.files ^Drive drive-service)
                assert)
        files-get (doto (.get files file-id)
                    assert)
        file (cast File (doto (.execute files-get)
                          assert))
        file (doto (.setDescription ^File file description)
               assert)
        update-request (doto (.update files file-id file)
                         assert)]
    (cast File (doto (.execute update-request)
                 assert))))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;; File Properties Management ;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

#_(t/ann get-properties [cred/GoogleCtx String -> (t/Seq Property)])
#_(defn get-properties
  "Given a google-ctx configuration map, and a file id, returns a
   list of all Properties associated with this file"
  [google-ctx file-id]
  (let [drive-service (build-drive-service google-ctx)
        properties (doto (.properties ^Drive drive-service)
                     assert)
        all-properties (doto (.list properties file-id)
                         assert)
        properties (cast PropertyList (doto (.execute all-properties)
                                        assert))]
    (tu/ignore-with-unchecked-cast
     (.getItems ^PropertyList properties)
     (t/Seq Property))))

#_(t/ann update-property [cred/GoogleCtx String String String String -> Property])
#_(defn update-property
  "Given a google-ctx configuration map, a file id, a key, a value, and
   a visibility(public or private) updates the property on this file to
   the new value if a property with the given key already exists, otherwise
   create a new one with this key value pair"
  [google-ctx file-id key value visibility]
  (let [drive-service (build-drive-service google-ctx)
        properties (doto (.properties ^Drive drive-service)
                     assert)
        property (doto (Property.)
                   (.setKey key)
                   (.setValue value)
                   (.setVisibility visibility))
        update-request (doto (.update properties file-id key property)
                         assert
                         (.setVisibility visibility))]
    (cast Property (doto (.execute update-request)
                     assert))))

#_(t/ann delete-property [cred/GoogleCtx String String String -> t/Any])
#_(defn delete-property
  "Given a google-ctx configuration map, a file id, and a key,
   deletes the property on this file associated with this key"
  [google-ctx file-id key visibility]
  (let [drive-service (build-drive-service google-ctx)
        properties (doto (.properties ^Drive drive-service)
                     assert)
        delete-request (doto (.delete properties file-id key)
                         assert
                         (.setVisibility visibility))]
    (.execute delete-request)))

;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;; File Permissions Management ;;;;;;;;;;;;;;;;;;;;;;;;;;
;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;;

#_(t/ann get-permissions [cred/GoogleCtx String -> (t/Seq Permission)])
#_(defn get-permissions
  "Given a google-ctx configuration map, and a file-id, gets all of the
   permissions for the given file"
  [google-ctx file-id]
  (let [drive-service (build-drive-service google-ctx)
        permissions (doto (.permissions ^Drive drive-service)
                     assert)
        all-permissions (doto (.list permissions file-id)
                          assert)
        permissions (cast PermissionList (doto (.execute all-permissions)
                                           assert))]
    (tu/ignore-with-unchecked-cast
     (.getItems ^PermissionList permissions)
     (t/Seq Permission))))

#_(t/ann update-permission [cred/GoogleCtx String String String -> Permission])
#_(defn update-permission
  "Given a google-ctx configuration map, a file-id, an email address of the
   user who's permissions we are editing, and a new role for the user on this
   file(reader or writer, owner is not currently supported), adds or edits the
   permissions for this user on the given file"
  [google-ctx file-id email new-role]
  (let [drive-service (build-drive-service google-ctx)
        permissions (doto (.permissions ^Drive drive-service)
                      assert)
        permissions-for-file (tu/ignore-with-unchecked-cast
                              (set (map #(get % "emailAddress")
                                        (get-permissions google-ctx file-id)))
                              (t/Set String))
        id-request (doto (.getIdForEmail permissions email)
                        assert)
        permission-id (cast PermissionId (doto (.execute id-request)
                                              assert))
        permission-id (doto (.getId ^PermissionId permission-id)
                        assert)
        permission (doto (Permission.)
                     (.setEmailAddress email)
                     (.setRole new-role)
                     (.setId permission-id)
                     (.setType "user"))
        request (if (contains? permissions-for-file email)
                  (doto (.update permissions file-id permission-id permission)
                    assert)
                  (doto (.insert permissions file-id permission)
                    assert))]
    (tu/ignore-with-unchecked-cast (.execute request)
                                   Permission)))

#_(t/ann remove-permission [cred/GoogleCtx String String -> t/Any])
#_(defn remove-permission
  "Given a google-ctx configuration map, a file-id, and  an email address
   of the user who's permissions we are editing, removes this user from
   the permissions of the given file"
  [google-ctx file-id email]
  (let [drive-service (build-drive-service google-ctx)
        permissions (doto (.permissions ^Drive drive-service)
                      assert)
        permissions-for-file (tu/ignore-with-unchecked-cast
                              (set (map #(get % "emailAddress")
                                        (get-permissions google-ctx file-id)))
                              (t/Set String))
        id-request (doto (.getIdForEmail permissions email)
                        assert)
        permission-id (cast PermissionId (doto (.execute id-request)
                                              assert))
        permission-id (doto (.getId ^PermissionId permission-id)
                        assert)
        delete-request (doto (.delete permissions file-id permission-id)
                         assert)]
    (if (contains? permissions-for-file email)
      (.execute delete-request))))
