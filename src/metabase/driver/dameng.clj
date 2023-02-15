(ns metabase.driver.dameng
  "DAMENG database driver of Metabase (as of Feb 2023)."
  (:require [clojure.java.jdbc :as jdbc]
            [clojure.set :as set]
            [clojure.string :as str]
            [clojure.tools.logging :as log]
            [honeysql.core :as hsql]
            [honeysql.format :as hformat]
            [java-time :as t]
            [metabase.config :as config]
            [metabase.db.spec :as db.spec]
            [metabase.driver :as driver]
            [metabase.driver.common :as driver.common]
            [metabase.driver.sql-jdbc.common :as sql-jdbc.common]
            [metabase.driver.sql-jdbc.connection :as sql-jdbc.conn]
            [metabase.driver.sql-jdbc.execute :as sql-jdbc.execute]
            [metabase.driver.sql-jdbc.sync :as sql-jdbc.sync]
            [metabase.driver.sql.query-processor :as sql.qp]
            [metabase.driver.sql.util.unprepare :as unprepare]
            [metabase.models.secret :as secret]
            [metabase.query-processor.store :as qp.store]
            [metabase.util :as u]
            [metabase.util.date-2 :as u.date]
            [metabase.util.honeysql-extensions :as hx]
            [metabase.util.i18n :refer [trs]]
            [potemkin :as p]
            [pretty.core :refer [PrettyPrintable]])
  (:import [java.sql ResultSet ResultSetMetaData Time Types]
           [java.time LocalDateTime OffsetDateTime OffsetTime]
           [java.util Date UUID]))

(driver/register! :dameng, :parent :sql-jdbc)

;;; +----------------------------------------------------------------------------------------------------------------+
;;; |                                             metabase.driver impls                                              |
;;; +----------------------------------------------------------------------------------------------------------------+

(defmethod sql-jdbc.conn/connection-details->spec :dameng
  [_
   {:keys [user password dbname host port ssl use-no-proxy]
    :or
    {user "SYSDBA" password "SYSDBA" dbname "SYS" host "localhost" port "5236"}
    :as details}]
  (->
   {:classname "dm.jdbc.driver.DmDriver"
    :subprotocol "dm"
    :subname (str "//" host ":" port "?schema=" dbname)
    :password password
    :user user
    :ssl (boolean ssl)
    :use_no_proxy (boolean use-no-proxy)
    :use_server_time_zone_for_dates true
    ;; temporary hardcode until we get product_name setting with JDBC driver v0.4.0
    :client_name "metabase/1.0.1 dameng-jdbc"}
   (sql-jdbc.common/handle-additional-options details :separator-style :url)))

(def ^:private database-type->base-type
  (sql-jdbc.sync/pattern-based-database-type->base-type
   [
    [#"INTEGER"       :type/Integer]
    [#"TIMESTAMP"     :type/DateTime]
    [#"VARCHAR"       :type/Text]
    [#"BIGINT"        :type/BigInteger]
    [#"VARBINARY"     :type/*]
    [#"CHAR"          :type/Text]
    [#"CLOB"          :type/Text]
    [#"DEC"           :type/Decimal]
    [#"BLOB"          :type/*]
    [#"INT"           :type/Integer]
    [#"DATE"          :type/Date]
    [#"NUMBER"        :type/Decimal]
    [#"TINYINT"       :type/Integer]
    [#"BYTE"          :type/Integer]
    [#"SMALLINT"      :type/Integer]
    [#"BINARY"        :type/*]
    [#"FLOAT"         :type/Float]
    [#"DOUBLE"        :type/Float]
    [#"BIT"           :type/*]
    [#"TIME"          :type/Time]
    [#"DATETIME"      :type/DateTime]
    [#"TEXT"          :type/Text]
    [#"LONG"          :type/Text]
    [#"LONGVARCHAR"   :type/Text]
    [#"IMAGE"         :type/*]
    [#"BFILE"         :type/*]
    [#"PLS_INTEGER"   :type/Integer]
    [#"VARCHAR2"      :type/Text]
    [#"REA"           :type/Float]
    [#"CHARACTER"     :type/Text]
    [#"BOO"           :type/Boolean]
    [#"BIT VARYING"   :type/*]
    [#"CHARACTER VARYING"                 :type/Text]
    [#"DOUBLE PRECISION"                  :type/Float]
    [#"TIME WITH TIME ZONE"               :type/Time]
    [#"TIME WITHOUT TIME ZONE"            :type/Time]
    [#"TIMESTAMP WITH TIME ZONE"          :type/DateTime]
    [#"TIMESTAMP WITHOUT TIME ZONE"       :type/DateTime]
    [#"TIMESTAMP WITH LOCAL TIME ZONE"    :type/DateTime]
    [#"TIMESTAMP WITHOUT LOCAL TIME ZONE" :type/DateTime]
   ]))

(defmethod sql-jdbc.sync/database-type->base-type :dameng
  [_ database-type]
  (let [base-type (database-type->base-type
                   (str/replace (name database-type)
                                #"(?:Nullable|LowCardinality)\((\S+)\)"
                                "$1"))]
    base-type))