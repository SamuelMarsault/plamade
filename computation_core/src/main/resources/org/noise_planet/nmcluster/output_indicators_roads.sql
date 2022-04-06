--DROP TABLE IF EXISTS ALL_UUEID;
--create table ALL_UUEID(UUEID varchar) AS SELECT DISTINCT UUEID FROM ROADS UNION ALL SELECT DISTINCT UUEID FROM RAIL_SECTIONS;
--DROP TABLE IF EXISTS EXPOSURE_AREA;
--create table EXPOSURE_AREA (noiseLevel varchar, uueid varchar, areaSquareKilometer double);
--drop table if exists noise_level_class;
--create table noise_level_class(exposureNoiseLevel varchar, noiseLevel varchar);
--insert into noise_level_class values ('Lden55', 'Lden5559'), ('Lden55', 'Lden6064'), ('Lden55', 'Lden6569'), ('Lden55', 'Lden7074'),  ('Lden55', 'LdenGreaterThan75'),
-- ('Lden65', 'Lden6569'), ('Lden65', 'Lden7074'),  ('Lden65', 'LdenGreaterThan75') ,
--('Lden75', 'LdenGreaterThan75'), ('Lden5559',  'Lden5559'), ('Lden6064',  'Lden6064'), ('Lden6569', 'Lden6569'), ('Lden7074', 'Lden7074'),  ('LdenGreaterThan75', 'LdenGreaterThan75'),
--('Lnight5054','Lnight5054'), ('Lnight5559','Lnight5559'), ('Lnight6064','Lnight6064'), ('Lnight6569','Lnight6569'), ('LnightGreaterThan70','LnightGreaterThan70');
--insert into EXPOSURE_AREA select distinct exposureNoiseLevel, UUEID, 0 FROM noise_level_class, ALL_UUEID;
--UPDATE EXPOSURE_AREA SET areaSquareKilometer = COALESCE((SELECT SUM(ST_AREA(THE_GEOM) / 1e-6) FROM CBS_A_R_LD_FRL02 C, noise_level_class N WHERE EXPOSURE_AREA.NOISELEVEL = N.exposureNoiseLevel AND N.noiseLevel = C.NOISELEVEL AND C.UUEID = EXPOSURE_AREA.UUEID GROUP BY exposureNoiseLevel), areaSquareKilometer);
--UPDATE EXPOSURE_AREA SET areaSquareKilometer = COALESCE((SELECT SUM(ST_AREA(THE_GEOM) / 1e-6) FROM CBS_A_R_LN_FRL02 C, noise_level_class N WHERE EXPOSURE_AREA.NOISELEVEL = N.exposureNoiseLevel AND N.noiseLevel = C.NOISELEVEL AND C.UUEID = EXPOSURE_AREA.UUEID GROUP BY exposureNoiseLevel), areaSquareKilometer);
--
--drop table if exists receiver_expo;
--create table receiver_expo as SELECT PK_1, LAEQ, BUILD_PK, PERCENT_RANK() OVER (PARTITION BY BUILD_PK ORDER BY LAEQ DESC, PK_1) RECEIVER_RANK  FROM LDEN_ROADS L, RECEIVERS_UUEID RU, RECEIVERS_BUILDING RB WHERE RCV_TYPE = 1 AND L.IDRECEIVER = RU.PK AND PK_1 = RB.PK order by BUILD_PK, LAEQ DESC;
--DELETE FROM receiver_expo WHERE RECEIVER_RANK > 0.5;
--
-- -- Calculs ERPS
--CREATE TABLE RECEIVERS_BUILD_DEN AS SELECT sourceType,aden.UUEID, b.PK_1 as PK, aden.laeqpa_sum as lden FROM RECEIVERS_SUM_LAEQPA_DEN aden, receivers b WHERE aden.idreceiver=b.PK AND RCV_TYPE=1
--CREATE TABLE RECEIVERS_BUILD_NIGHT AS SELECT sourceType,an.UUEID, b.PK_1 as PK, an.laeqpa_sum as lnight FROM RECEIVERS_SUM_LAEQPA_NIGHT an, receivers b WHERE an.idreceiver=b.PK AND RCV_TYPE=1
--CREATE TABLE RECEIVERS_BUILD_POP_DEN AS SELECT sourceType,a.UUEID, a.PK, b.BUILD_PK, c.ID_BAT, a.lden-3 as lden, b.pop as POP, "+ratioPopLog+" ratioPopLog, c.AGGLO FROM RECEIVERS_BUILD_DEN a, receivers_BUILDING b, BUILDINGS_SCREENS c WHERE b.BUILD_PK = c.PK AND a.PK=b.PK ;
--CREATE TABLE RECEIVERS_BUILD_POP_NIGHT AS SELECT sourceType,a.UUEID, a.PK, b.BUILD_PK, c.ID_BAT, a.lnight-3 as lnight, b.pop as POP, "+ratioPopLog+" ratioPopLog, c.AGGLO FROM RECEIVERS_BUILD_NIGHT a, receivers_BUILDING b, BUILDINGS_SCREENS c WHERE b.BUILD_PK = c.PK AND a.PK=b.PK;
--DROP TABLE IF EXISTS BUILD_MAX_DEN, BUILD_MAX_NIGHT, BUILD_MAX_DEN_NOT_AGGLO, BUILDING_HOSPITAL_NIGHT, BUILD_MAX_NIGHT_NOT_AGGLO, BUILDING_SCHOOL, BUILDING_SCHOOL_DEN, BUILDING_SCHOOL_DEN_NOT_AGGLO, BUILDING_SCHOOL_NIGHT, BUILDING_SCHOOL_NIGHT_NOT_AGGLO, BUILDING_HOSPITAL, BUILDING_HOSPITAL_DEN, BUILDING_HOSPITAL_DEN, BUILDING_HOSPITAL_DEN_NOT_AGGLO, BUILDING_HOSPITAL_NIGHT_NOT_AGGLO, BUILDING_COUNT_1, BUILDING_COUNT;
--CREATE TABLE BUILD_MAX_DEN AS SELECT sourceType,UUEID, BUILD_PK, ID_BAT, max(lden) as lden , AGGLO FROM RECEIVERS_BUILD_POP_DEN GROUP BY UUEID, BUILD_PK;
--CREATE TABLE BUILD_MAX_NIGHT AS SELECT sourceType,UUEID, BUILD_PK, ID_BAT, max(lnight) as lnight , AGGLO FROM RECEIVERS_BUILD_POP_NIGHT  GROUP BY UUEID, BUILD_PK;
--CREATE TABLE BUILDING_SCHOOL AS SELECT id_erps, id_bat FROM BUILDINGS_ERPS WHERE ERPS_NATUR='Enseignement';
--CREATE TABLE BUILDING_SCHOOL_DEN AS SELECT sourceType,b.uueid, a.id_erps, MAX(b.LDEN) as LDEN, AGGLO FROM BUILDING_SCHOOL a, BUILD_MAX_DEN b WHERE a.ID_BAT=b.ID_BAT group by b.uueid, a.id_erps, b.AGGLO;
--CREATE TABLE BUILDING_SCHOOL_NIGHT AS SELECT sourceType,b.uueid, a.id_erps, MAX(b.LNIGHT) as LNIGHT, AGGLO FROM BUILDING_SCHOOL a, BUILD_MAX_NIGHT b WHERE a.ID_BAT=b.ID_BAT group by b.uueid, a.id_erps, b.AGGLO;
--CREATE TABLE BUILDING_HOSPITAL AS SELECT id_erps, id_bat FROM BUILDINGS_ERPS WHERE ERPS_NATUR='Sante';
--CREATE TABLE BUILDING_HOSPITAL_DEN AS SELECT sourceType,b.uueid, a.id_erps, MAX(b.LDEN) as LDEN, AGGLO FROM BUILDING_HOSPITAL a, BUILD_MAX_DEN b WHERE a.ID_BAT=b.ID_BAT group by b.uueid, a.id_erps, b.AGGLO;
--CREATE TABLE BUILDING_HOSPITAL_NIGHT AS SELECT sourceType,b.uueid, a.id_erps, MAX(b.LNIGHT) as LNIGHT, AGGLO FROM BUILDING_HOSPITAL a, BUILD_MAX_NIGHT b WHERE a.ID_BAT=b.ID_BAT group by b.uueid, a.id_erps, b.AGGLO;

-- Calcul ERPS nico
SET @UUEID=$UUEID

drop index if exists RECEIVERS_BUILDING_BUILD_PK;
create index RECEIVERS_BUILDING_BUILD_PK on RECEIVERS_BUILDING (BUILD_PK);

DROP TABLE IF EXISTS BUILDING_MAX_DEN,BUILDING_MAX_NIGHT, BUILDING_SCHOOL_MAX_DEN, BUILDING_SCHOOL_MAX_NIGHT, BUILDING_HOSPITAL_MAX_DEN, BUILDING_HOSPITAL_MAX_NIGHT;
CREATE TABLE BUILDING_MAX_DEN AS SELECT BUILD_PK, ID_BAT, AGGLO, max(LAEQ) - 3 as max_lden FROM BUILDINGS_SCREENS B INNER JOIN RECEIVERS_BUILDING RB ON (B.PK = RB.BUILD_PK) INNER JOIN RECEIVERS_UUEID  RU ON (RB.PK = RU.PK_1 AND RU.RCV_TYPE = 1) INNER JOIN LDEN_ROADS LR ON (RU.PK = LR.IDRECEIVER) GROUP BY BUILD_PK, ID_BAT, AGGLO;
CREATE TABLE BUILDING_MAX_NIGHT AS SELECT BUILD_PK, ID_BAT, AGGLO, max(LAEQ) - 3 as max_ln FROM BUILDINGS_SCREENS B INNER JOIN RECEIVERS_BUILDING RB ON (B.PK = RB.BUILD_PK) INNER JOIN RECEIVERS_UUEID  RU ON (RB.PK = RU.PK_1 AND RU.RCV_TYPE = 1) INNER JOIN LNIGHT_ROADS LR ON (RU.PK = LR.IDRECEIVER) GROUP BY BUILD_PK, ID_BAT, AGGLO;

CREATE TABLE BUILDING_SCHOOL_MAX_DEN AS SELECT B.PK,id_erps, B.ID_BAT, B.AGGLO, max(LAEQ) - 3 as max_lden FROM  BUILDINGS_ERPS BR
INNER JOIN  BUILDINGS_SCREENS B ON (BR.ID_BAT = B.ID_BAT AND BR.ERPS_NATUR='Enseignement')
 INNER JOIN RECEIVERS_BUILDING RB ON (B.PK = RB.BUILD_PK)
  INNER JOIN RECEIVERS_UUEID  RU ON (RB.PK = RU.PK_1 AND RU.RCV_TYPE = 1)
  INNER JOIN LDEN_ROADS LR ON (RU.PK = LR.IDRECEIVER) GROUP BY B.PK, id_erps, B.ID_BAT, B.AGGLO;

CREATE TABLE BUILDING_SCHOOL_MAX_NIGHT AS SELECT B.PK,id_erps, B.ID_BAT, B.AGGLO, max(LAEQ) - 3 as max_lden FROM  BUILDINGS_ERPS BR
INNER JOIN  BUILDINGS_SCREENS B ON (BR.ID_BAT = B.ID_BAT AND BR.ERPS_NATUR='Enseignement')
 INNER JOIN RECEIVERS_BUILDING RB ON (B.PK = RB.BUILD_PK)
  INNER JOIN RECEIVERS_UUEID  RU ON (RB.PK = RU.PK_1 AND RU.RCV_TYPE = 1)
  INNER JOIN LNIGHT_ROADS LR ON (RU.PK = LR.IDRECEIVER) GROUP BY B.PK, id_erps, B.ID_BAT, B.AGGLO;

CREATE TABLE BUILDING_HOSPITAL_MAX_DEN AS SELECT B.PK,id_erps, B.ID_BAT, B.AGGLO, max(LAEQ) - 3 as max_lden FROM  BUILDINGS_ERPS BR
INNER JOIN  BUILDINGS_SCREENS B ON (BR.ID_BAT = B.ID_BAT AND BR.ERPS_NATUR='Sante')
 INNER JOIN RECEIVERS_BUILDING RB ON (B.PK = RB.BUILD_PK)
  INNER JOIN RECEIVERS_UUEID  RU ON (RB.PK = RU.PK_1 AND RU.RCV_TYPE = 1)
  INNER JOIN LDEN_ROADS LR ON (RU.PK = LR.IDRECEIVER) GROUP BY B.PK, id_erps, B.ID_BAT, B.AGGLO;

CREATE TABLE BUILDING_HOSPITAL_MAX_NIGHT AS SELECT B.PK,id_erps, B.ID_BAT, B.AGGLO, max(LAEQ) - 3 as max_lden FROM  BUILDINGS_ERPS BR
INNER JOIN  BUILDINGS_SCREENS B ON (BR.ID_BAT = B.ID_BAT AND BR.ERPS_NATUR='Sante')
 INNER JOIN RECEIVERS_BUILDING RB ON (B.PK = RB.BUILD_PK)
  INNER JOIN RECEIVERS_UUEID  RU ON (RB.PK = RU.PK_1 AND RU.RCV_TYPE = 1)
  INNER JOIN LNIGHT_ROADS LR ON (RU.PK = LR.IDRECEIVER) GROUP BY B.PK, id_erps, B.ID_BAT, B.AGGLO;