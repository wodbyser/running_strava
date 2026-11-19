--
-- PostgreSQL database dump
--

\restrict HBnPmAP9qNMw9J9yaEl8IUckRLVWsLiYaq1SRTNgPwtVsQMlcInhrwzEHyg63De

-- Dumped from database version 17.9 (Debian 17.9-1.pgdg13+1)
-- Dumped by pg_dump version 17.9 (Debian 17.9-1.pgdg13+1)

SET statement_timeout = 0;
SET lock_timeout = 0;
SET idle_in_transaction_session_timeout = 0;
SET transaction_timeout = 0;
SET client_encoding = 'UTF8';
SET standard_conforming_strings = on;
SELECT pg_catalog.set_config('search_path', '', false);
SET check_function_bodies = false;
SET xmloption = content;
SET client_min_messages = warning;
SET row_security = off;

SET default_tablespace = '';

SET default_table_access_method = heap;

--
-- Name: ACTIVITIES; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."ACTIVITIES" (
    "ID" bigint NOT NULL,
    "AVERAGE_CADENCE" real,
    "AVERAGE_HEARTRATE" real,
    "AVERAGE_SPEED" real,
    "AVERAGE_WATTS" real,
    "CALORIES" real,
    "DESCRIPTION" character varying,
    "DEVICE_WATTS" boolean,
    "DISTANCE" real NOT NULL,
    "ELAPSED_TIME" integer,
    "ELEV_HIGH" real,
    "ELEV_LOW" real,
    "END_LATLNG" character varying(255),
    "GEAR_ID" character varying(255),
    "HAS_HEARTRATE" boolean,
    "IS_COMMUTE" boolean,
    "IS_FLAGGED" boolean,
    "IS_MANUAL" boolean,
    "IS_TRAINER" boolean,
    "KILOJOULES" real,
    "MAX_HEARTRATE" real,
    "MAX_SPEED" real,
    "MAX_WATTS" real,
    "MOVING_TIME" integer,
    "NAME" character varying(255),
    "SPORT_TYPE" character varying(255),
    "START_DATE" timestamp with time zone,
    "START_LATLNG" character varying(255),
    "SUFFER_SCORE" integer,
    "TIMEZONE" character varying(255),
    "TOTAL_ELEVATION_GAIN" real,
    "TYPE" character varying(255),
    "WEIGHTED_AVERAGE_WATTS" integer,
    "WORKOUT_TYPE" integer
);


--
-- Name: ACTIVITY_STREAMS; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."ACTIVITY_STREAMS" (
    "ACTIVITY_ID" bigint NOT NULL,
    "ALTITUDE" character varying,
    "CADENCE" character varying,
    "DISTANCE" character varying,
    "GRADE_SMOOTH" character varying,
    "HEARTRATE" character varying,
    "LATLNG" character varying,
    "TEMP" character varying,
    "TIME" character varying,
    "VELOCITY_SMOOTH" character varying
);


--
-- Name: STRAVA_TOKENS; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."STRAVA_TOKENS" (
    "ID" bigint NOT NULL,
    "ACCESS_TOKEN" character varying(512) NOT NULL,
    "ATHLETE_ID" bigint,
    "EXPIRES_AT" bigint NOT NULL,
    "REFRESH_TOKEN" character varying(512) NOT NULL
);


--
-- Name: SYNC_STATUS; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public."SYNC_STATUS" (
    "ID" bigint NOT NULL,
    "ACTIVITIES_COUNT" integer,
    "IS_SYNCING" boolean,
    "LAST_ACTIVITY_ID" bigint,
    "LAST_SYNC_AT" timestamp with time zone
);


--
-- Name: activities; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.activities (
    id bigint NOT NULL,
    average_cadence real,
    average_heartrate real,
    average_speed real,
    average_watts real,
    calories real,
    description text,
    device_watts boolean,
    distance real NOT NULL,
    elapsed_time integer,
    elev_high real,
    elev_low real,
    end_latlng character varying(255),
    gear_id character varying(255),
    has_heartrate boolean,
    is_commute boolean,
    is_flagged boolean,
    is_manual boolean,
    is_trainer boolean,
    kilojoules real,
    max_heartrate real,
    max_speed real,
    max_watts real,
    moving_time integer,
    name character varying(255),
    sport_type character varying(255),
    start_date timestamp with time zone,
    start_latlng character varying(255),
    suffer_score integer,
    timezone character varying(255),
    total_elevation_gain real,
    type character varying(255),
    weighted_average_watts integer,
    workout_type integer,
    best_efforts_json text,
    device_name character varying(255),
    external_id character varying(255)
);


--
-- Name: activity_laps; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.activity_laps (
    id bigint NOT NULL,
    activity_id bigint,
    average_cadence real,
    average_heartrate real,
    average_speed real,
    distance real NOT NULL,
    elapsed_time integer,
    end_index integer,
    lap_index integer,
    max_heartrate real,
    max_speed real,
    moving_time integer,
    name character varying(255),
    split integer NOT NULL,
    start_date timestamp with time zone,
    start_index integer
);


--
-- Name: activity_streams; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.activity_streams (
    activity_id bigint NOT NULL,
    altitude text,
    cadence text,
    distance text,
    grade_smooth text,
    heartrate text,
    latlng text,
    temp text,
    "time" text,
    velocity_smooth text
);


--
-- Name: garmin_activity_ref; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.garmin_activity_ref (
    activity_id bigint NOT NULL,
    training_load double precision,
    aerobic_te double precision,
    anaerobic_te double precision,
    avg_hr double precision,
    calendar_date date,
    distance_m double precision,
    duration_s double precision,
    elapsed_s double precision,
    is_manual boolean,
    manufacturer character varying(255),
    max_hr double precision,
    moving_s double precision,
    parent_type_id integer,
    original_units character varying(300),
    raw_payload_id bigint,
    source_endpoint character varying(200),
    source_timestamp timestamp(6) with time zone,
    synced_at timestamp(6) with time zone,
    start_lat double precision,
    start_lon double precision,
    start_time_gmt timestamp(6) with time zone,
    training_effect_label character varying(255),
    type_key character varying(255)
);


--
-- Name: garmin_body_battery_sample; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.garmin_body_battery_sample (
    timestamp_gmt_ms bigint NOT NULL,
    calendar_date date,
    level integer NOT NULL,
    original_units character varying(300),
    raw_payload_id bigint,
    source_endpoint character varying(200),
    source_timestamp timestamp(6) with time zone,
    synced_at timestamp(6) with time zone
);


--
-- Name: garmin_daily_body_battery; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.garmin_daily_body_battery (
    calendar_date date NOT NULL,
    charged integer,
    drained integer,
    latest_value integer,
    max_value integer,
    min_value integer,
    original_units character varying(300),
    raw_payload_id bigint,
    source_endpoint character varying(200),
    source_timestamp timestamp(6) with time zone,
    synced_at timestamp(6) with time zone
);


--
-- Name: garmin_daily_hrv; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.garmin_daily_hrv (
    calendar_date date NOT NULL,
    baseline_balanced_low integer,
    baseline_balanced_upper integer,
    baseline_low_upper integer,
    baseline_marker_value double precision,
    feedback_phrase character varying(255),
    last_night_5min_high integer,
    last_night_avg integer,
    original_units character varying(300),
    raw_payload_id bigint,
    source_endpoint character varying(200),
    source_timestamp timestamp(6) with time zone,
    synced_at timestamp(6) with time zone,
    status character varying(255),
    weekly_avg integer
);


--
-- Name: garmin_daily_resting_hr; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.garmin_daily_resting_hr (
    calendar_date date NOT NULL,
    max_hr integer,
    min_hr integer,
    original_units character varying(300),
    raw_payload_id bigint,
    source_endpoint character varying(200),
    source_timestamp timestamp(6) with time zone,
    synced_at timestamp(6) with time zone,
    resting_hr integer
);


--
-- Name: garmin_daily_sleep; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.garmin_daily_sleep (
    calendar_date date NOT NULL,
    avg_heart_rate double precision,
    avg_respiration double precision,
    avg_sleep_stress double precision,
    awake_sleep_seconds integer,
    deep_sleep_seconds integer,
    garmin_sleep_need_actual integer,
    garmin_sleep_need_baseline integer,
    light_sleep_seconds integer,
    nap_time_seconds integer,
    overall_qualifier character varying(255),
    overall_score integer,
    original_units character varying(300),
    raw_payload_id bigint,
    source_endpoint character varying(200),
    source_timestamp timestamp(6) with time zone,
    synced_at timestamp(6) with time zone,
    rem_sleep_seconds integer,
    sleep_end_gmt_ms bigint,
    sleep_start_gmt_ms bigint,
    sleep_time_seconds integer,
    unmeasurable_sleep_seconds integer
);


--
-- Name: garmin_daily_steps; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.garmin_daily_steps (
    calendar_date date NOT NULL,
    distance_m double precision,
    original_units character varying(300),
    raw_payload_id bigint,
    source_endpoint character varying(200),
    source_timestamp timestamp(6) with time zone,
    synced_at timestamp(6) with time zone,
    step_goal integer,
    steps integer
);


--
-- Name: garmin_daily_stress; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.garmin_daily_stress (
    calendar_date date NOT NULL,
    activity_duration_ms bigint,
    avg_level integer,
    high_duration_ms bigint,
    low_duration_ms bigint,
    max_level integer,
    medium_duration_ms bigint,
    original_units character varying(300),
    raw_payload_id bigint,
    source_endpoint character varying(200),
    source_timestamp timestamp(6) with time zone,
    synced_at timestamp(6) with time zone,
    rest_duration_ms bigint,
    total_duration_ms bigint,
    uncategorized_duration_ms bigint
);


--
-- Name: garmin_hr_zones; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.garmin_hr_zones (
    sport character varying(255) NOT NULL,
    lactate_threshold_hr integer,
    max_hr integer,
    original_units character varying(300),
    raw_payload_id bigint,
    source_endpoint character varying(200),
    source_timestamp timestamp(6) with time zone,
    synced_at timestamp(6) with time zone,
    resting_hr integer,
    training_method character varying(255),
    zone1_floor integer,
    zone2_floor integer,
    zone3_floor integer,
    zone4_floor integer,
    zone5_floor integer
);


--
-- Name: garmin_raw_payload; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.garmin_raw_payload (
    id bigint NOT NULL,
    endpoint character varying(200),
    payload text,
    record_key character varying(100),
    synced_at timestamp(6) with time zone
);


--
-- Name: garmin_raw_payload_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.garmin_raw_payload ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.garmin_raw_payload_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: garmin_sync_status; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.garmin_sync_status (
    id bigint NOT NULL,
    failed_calls integer,
    last_error_category character varying(255),
    last_error_detail character varying(500),
    last_run_finished_at timestamp(6) with time zone,
    last_run_kind character varying(255),
    last_run_started_at timestamp(6) with time zone,
    last_success_at timestamp(6) with time zone,
    last_success_date date,
    null_fields_per_endpoint text,
    records_per_endpoint text
);


--
-- Name: race_goal; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.race_goal (
    id bigint NOT NULL,
    created_at timestamp(6) with time zone NOT NULL,
    distance_m double precision NOT NULL,
    name character varying(120),
    race_date date NOT NULL,
    target_seconds integer
);


--
-- Name: race_goal_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.race_goal ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.race_goal_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: race_prediction_log; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.race_prediction_log (
    id bigint NOT NULL,
    algorithm_version character varying(20) NOT NULL,
    anchor_count integer NOT NULL,
    capped_by_activity_id bigint,
    distance_m double precision NOT NULL,
    high_seconds double precision,
    log_date date NOT NULL,
    low_seconds double precision,
    seconds double precision NOT NULL,
    updated_at timestamp(6) with time zone NOT NULL
);


--
-- Name: race_prediction_log_id_seq; Type: SEQUENCE; Schema: public; Owner: -
--

ALTER TABLE public.race_prediction_log ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME public.race_prediction_log_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);


--
-- Name: strava_tokens; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.strava_tokens (
    id bigint NOT NULL,
    access_token character varying(512) NOT NULL,
    athlete_id bigint,
    expires_at bigint NOT NULL,
    refresh_token character varying(512) NOT NULL,
    resting_hr integer
);


--
-- Name: sync_status; Type: TABLE; Schema: public; Owner: -
--

CREATE TABLE public.sync_status (
    id bigint NOT NULL,
    activities_count integer,
    is_syncing boolean,
    last_activity_id bigint,
    last_sync_at timestamp with time zone
);


--
-- Name: activities activities_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.activities
    ADD CONSTRAINT activities_pkey PRIMARY KEY (id);


--
-- Name: activity_laps activity_laps_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.activity_laps
    ADD CONSTRAINT activity_laps_pkey PRIMARY KEY (id);


--
-- Name: activity_streams activity_streams_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.activity_streams
    ADD CONSTRAINT activity_streams_pkey PRIMARY KEY (activity_id);


--
-- Name: garmin_activity_ref garmin_activity_ref_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.garmin_activity_ref
    ADD CONSTRAINT garmin_activity_ref_pkey PRIMARY KEY (activity_id);


--
-- Name: garmin_body_battery_sample garmin_body_battery_sample_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.garmin_body_battery_sample
    ADD CONSTRAINT garmin_body_battery_sample_pkey PRIMARY KEY (timestamp_gmt_ms);


--
-- Name: garmin_daily_body_battery garmin_daily_body_battery_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.garmin_daily_body_battery
    ADD CONSTRAINT garmin_daily_body_battery_pkey PRIMARY KEY (calendar_date);


--
-- Name: garmin_daily_hrv garmin_daily_hrv_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.garmin_daily_hrv
    ADD CONSTRAINT garmin_daily_hrv_pkey PRIMARY KEY (calendar_date);


--
-- Name: garmin_daily_resting_hr garmin_daily_resting_hr_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.garmin_daily_resting_hr
    ADD CONSTRAINT garmin_daily_resting_hr_pkey PRIMARY KEY (calendar_date);


--
-- Name: garmin_daily_sleep garmin_daily_sleep_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.garmin_daily_sleep
    ADD CONSTRAINT garmin_daily_sleep_pkey PRIMARY KEY (calendar_date);


--
-- Name: garmin_daily_steps garmin_daily_steps_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.garmin_daily_steps
    ADD CONSTRAINT garmin_daily_steps_pkey PRIMARY KEY (calendar_date);


--
-- Name: garmin_daily_stress garmin_daily_stress_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.garmin_daily_stress
    ADD CONSTRAINT garmin_daily_stress_pkey PRIMARY KEY (calendar_date);


--
-- Name: garmin_hr_zones garmin_hr_zones_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.garmin_hr_zones
    ADD CONSTRAINT garmin_hr_zones_pkey PRIMARY KEY (sport);


--
-- Name: garmin_raw_payload garmin_raw_payload_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.garmin_raw_payload
    ADD CONSTRAINT garmin_raw_payload_pkey PRIMARY KEY (id);


--
-- Name: garmin_sync_status garmin_sync_status_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.garmin_sync_status
    ADD CONSTRAINT garmin_sync_status_pkey PRIMARY KEY (id);


--
-- Name: race_goal race_goal_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.race_goal
    ADD CONSTRAINT race_goal_pkey PRIMARY KEY (id);


--
-- Name: race_prediction_log race_prediction_log_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.race_prediction_log
    ADD CONSTRAINT race_prediction_log_pkey PRIMARY KEY (id);


--
-- Name: strava_tokens strava_tokens_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.strava_tokens
    ADD CONSTRAINT strava_tokens_pkey PRIMARY KEY (id);


--
-- Name: sync_status sync_status_pkey; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.sync_status
    ADD CONSTRAINT sync_status_pkey PRIMARY KEY (id);


--
-- Name: race_prediction_log uk5yqud6kioh24q68l6w8j38yna; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.race_prediction_log
    ADD CONSTRAINT uk5yqud6kioh24q68l6w8j38yna UNIQUE (log_date, distance_m);


--
-- Name: garmin_raw_payload uq_garmin_raw_endpoint_key; Type: CONSTRAINT; Schema: public; Owner: -
--

ALTER TABLE ONLY public.garmin_raw_payload
    ADD CONSTRAINT uq_garmin_raw_endpoint_key UNIQUE (endpoint, record_key);


--
-- PostgreSQL database dump complete
--

\unrestrict HBnPmAP9qNMw9J9yaEl8IUckRLVWsLiYaq1SRTNgPwtVsQMlcInhrwzEHyg63De

