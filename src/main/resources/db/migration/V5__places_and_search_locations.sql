-- Muslima Free - dashboard: "very strong" nearby search.
--
-- 1. A gazetteer (dashboard_places) of governorates, cities and districts with their centre coordinates, so that a
--    member who never shared a device location still has an approximate position: the centre of their city or, failing
--    that, their governorate (resolved from the free-text city / governorate of their profile).
-- 2. The search index (dashboard_search_locations): ONE effective position per member - fresh device location, else city
--    centre, else governorate centre - maintained by this service (LocationIndexer). Distance search, "nearest" and the
--    distance part of the relevance score read only this table.
-- 3. Anti-tracking on device locations: a daily cap on how often a member can move their position (repeated moves
--    are how an attacker trilaterates other members from the distances shown).

CREATE TABLE dashboard_places (
    id            INT          NOT NULL,
    country_code  CHAR(2)      NOT NULL,
    kind          VARCHAR(10)  NOT NULL,
    region_id     INT          NULL,
    name_ar       VARCHAR(100) NOT NULL,
    name_en       VARCHAR(100) NOT NULL,
    -- other spellings members type, '|'-separated (matching ignores diacritics, hamza forms, "ال" and case anyway)
    aliases       VARCHAR(300) NULL,
    latitude      DECIMAL(7,4) NOT NULL,
    longitude     DECIMAL(8,4) NOT NULL,
    CONSTRAINT pk_dashboard_places PRIMARY KEY (id),
    CONSTRAINT fk_dashboard_places_region FOREIGN KEY (region_id) REFERENCES dashboard_places (id),
    CONSTRAINT ck_dashboard_places_kind CHECK (kind IN ('REGION', 'CITY'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- Seed: Egypt's 27 governorates (centre = capital) with main cities and districts, plus Arab and diaspora cities.
-- Coordinates are city centres (a few km of tolerance is irrelevant at this granularity). Extend with new migrations.
INSERT INTO dashboard_places (id, country_code, kind, region_id, name_ar, name_en, aliases, latitude, longitude) VALUES
(1, 'EG', 'REGION', NULL, 'القاهرة', 'Cairo', NULL, 30.0444, 31.2357),
(2, 'EG', 'REGION', NULL, 'الجيزة', 'Giza', NULL, 30.0131, 31.2089),
(3, 'EG', 'REGION', NULL, 'الإسكندرية', 'Alexandria', 'alex', 31.2001, 29.9187),
(4, 'EG', 'REGION', NULL, 'القليوبية', 'Qalyubia', 'قليوب', 30.466, 31.1848),
(5, 'EG', 'REGION', NULL, 'الشرقية', 'Sharqia', NULL, 30.5877, 31.502),
(6, 'EG', 'REGION', NULL, 'الدقهلية', 'Dakahlia', NULL, 31.0409, 31.3785),
(7, 'EG', 'REGION', NULL, 'الغربية', 'Gharbia', NULL, 30.7865, 31.0004),
(8, 'EG', 'REGION', NULL, 'المنوفية', 'Monufia', 'menoufia', 30.5526, 31.0089),
(9, 'EG', 'REGION', NULL, 'البحيرة', 'Beheira', NULL, 31.0341, 30.4682),
(10, 'EG', 'REGION', NULL, 'كفر الشيخ', 'Kafr El Sheikh', NULL, 31.1107, 30.9388),
(11, 'EG', 'REGION', NULL, 'دمياط', 'Damietta', NULL, 31.4165, 31.8133),
(12, 'EG', 'REGION', NULL, 'بورسعيد', 'Port Said', 'بور سعيد', 31.2653, 32.3019),
(13, 'EG', 'REGION', NULL, 'الإسماعيلية', 'Ismailia', NULL, 30.5965, 32.2715),
(14, 'EG', 'REGION', NULL, 'السويس', 'Suez', NULL, 29.9668, 32.5498),
(15, 'EG', 'REGION', NULL, 'الفيوم', 'Faiyum', 'fayoum', 29.3084, 30.8428),
(16, 'EG', 'REGION', NULL, 'بني سويف', 'Beni Suef', NULL, 29.0661, 31.0994),
(17, 'EG', 'REGION', NULL, 'المنيا', 'Minya', NULL, 28.1099, 30.7503),
(18, 'EG', 'REGION', NULL, 'أسيوط', 'Asyut', 'assiut', 27.1783, 31.1859),
(19, 'EG', 'REGION', NULL, 'سوهاج', 'Sohag', NULL, 26.5591, 31.6957),
(20, 'EG', 'REGION', NULL, 'قنا', 'Qena', NULL, 26.1551, 32.716),
(21, 'EG', 'REGION', NULL, 'الأقصر', 'Luxor', NULL, 25.6872, 32.6396),
(22, 'EG', 'REGION', NULL, 'أسوان', 'Aswan', NULL, 24.0889, 32.8998),
(23, 'EG', 'REGION', NULL, 'البحر الأحمر', 'Red Sea', NULL, 27.2579, 33.8116),
(24, 'EG', 'REGION', NULL, 'الوادي الجديد', 'New Valley', NULL, 25.439, 30.5586),
(25, 'EG', 'REGION', NULL, 'مطروح', 'Matrouh', NULL, 31.3543, 27.2373),
(26, 'EG', 'REGION', NULL, 'شمال سيناء', 'North Sinai', NULL, 31.1316, 33.7984),
(27, 'EG', 'REGION', NULL, 'جنوب سيناء', 'South Sinai', NULL, 28.241, 33.622),
(28, 'EG', 'CITY', 1, 'المعادي', 'Maadi', NULL, 29.9602, 31.2569),
(29, 'EG', 'CITY', 1, 'مدينة نصر', 'Nasr City', NULL, 30.0566, 31.3301),
(30, 'EG', 'CITY', 1, 'مصر الجديدة', 'Heliopolis', NULL, 30.0911, 31.3227),
(31, 'EG', 'CITY', 1, 'القاهرة الجديدة', 'New Cairo', 'التجمع الخامس|التجمع|fifth settlement', 30.0074, 31.4913),
(32, 'EG', 'CITY', 1, 'حلوان', 'Helwan', NULL, 29.8414, 31.3008),
(33, 'EG', 'CITY', 1, 'الزمالك', 'Zamalek', NULL, 30.0609, 31.2197),
(34, 'EG', 'CITY', 1, 'شبرا', 'Shubra', NULL, 30.082, 31.245),
(35, 'EG', 'CITY', 1, 'المقطم', 'Mokattam', NULL, 30.0177, 31.3008),
(36, 'EG', 'CITY', 1, 'وسط البلد', 'Downtown Cairo', NULL, 30.0478, 31.2336),
(37, 'EG', 'CITY', 1, 'عين شمس', 'Ain Shams', NULL, 30.131, 31.3283),
(38, 'EG', 'CITY', 1, 'الشروق', 'El Shorouk', 'مدينة الشروق', 30.1286, 31.605),
(39, 'EG', 'CITY', 2, 'الدقي', 'Dokki', NULL, 30.0385, 31.2123),
(40, 'EG', 'CITY', 2, 'المهندسين', 'Mohandessin', NULL, 30.0566, 31.2003),
(41, 'EG', 'CITY', 2, 'الهرم', 'Haram', 'faisal|فيصل', 29.9892, 31.145),
(42, 'EG', 'CITY', 2, 'السادس من أكتوبر', '6th of October', '6 أكتوبر|اكتوبر|october', 29.9381, 30.9147),
(43, 'EG', 'CITY', 2, 'الشيخ زايد', 'Sheikh Zayed', NULL, 30.0444, 30.987),
(44, 'EG', 'CITY', 3, 'سموحة', 'Smouha', NULL, 31.215, 29.948),
(45, 'EG', 'CITY', 3, 'المنتزه', 'Montaza', NULL, 31.288, 30.016),
(46, 'EG', 'CITY', 3, 'العجمي', 'Agami', NULL, 31.094, 29.767),
(47, 'EG', 'CITY', 3, 'برج العرب', 'Borg El Arab', NULL, 30.918, 29.54),
(48, 'EG', 'CITY', 4, 'بنها', 'Banha', NULL, 30.466, 31.1848),
(49, 'EG', 'CITY', 4, 'شبرا الخيمة', 'Shubra El Kheima', NULL, 30.1286, 31.2422),
(50, 'EG', 'CITY', 4, 'العبور', 'Obour', 'مدينة العبور', 30.2283, 31.479),
(51, 'EG', 'CITY', 5, 'الزقازيق', 'Zagazig', NULL, 30.5877, 31.502),
(52, 'EG', 'CITY', 5, 'العاشر من رمضان', '10th of Ramadan', 'العاشر', 30.296, 31.742),
(53, 'EG', 'CITY', 6, 'المنصورة', 'Mansoura', NULL, 31.0409, 31.3785),
(54, 'EG', 'CITY', 7, 'طنطا', 'Tanta', NULL, 30.7865, 31.0004),
(55, 'EG', 'CITY', 7, 'المحلة الكبرى', 'El Mahalla El Kubra', 'المحلة', 30.9706, 31.1669),
(56, 'EG', 'CITY', 8, 'شبين الكوم', 'Shibin El Kom', NULL, 30.5526, 31.0089),
(57, 'EG', 'CITY', 9, 'دمنهور', 'Damanhur', NULL, 31.0341, 30.4682),
(58, 'EG', 'CITY', 23, 'الغردقة', 'Hurghada', NULL, 27.2579, 33.8116),
(59, 'EG', 'CITY', 24, 'الخارجة', 'Kharga', NULL, 25.439, 30.5586),
(60, 'EG', 'CITY', 25, 'مرسى مطروح', 'Marsa Matruh', NULL, 31.3543, 27.2373),
(61, 'EG', 'CITY', 26, 'العريش', 'Arish', NULL, 31.1316, 33.7984),
(62, 'EG', 'CITY', 27, 'الطور', 'El Tor', NULL, 28.241, 33.622),
(63, 'EG', 'CITY', 27, 'شرم الشيخ', 'Sharm El Sheikh', NULL, 27.9158, 34.33),
(64, 'SA', 'CITY', NULL, 'الرياض', 'Riyadh', NULL, 24.7136, 46.6753),
(65, 'SA', 'CITY', NULL, 'جدة', 'Jeddah', 'جده', 21.4858, 39.1925),
(66, 'SA', 'CITY', NULL, 'مكة المكرمة', 'Mecca', 'مكة|makkah', 21.3891, 39.8579),
(67, 'SA', 'CITY', NULL, 'المدينة المنورة', 'Medina', 'madinah', 24.5247, 39.5692),
(68, 'SA', 'CITY', NULL, 'الدمام', 'Dammam', NULL, 26.4207, 50.0888),
(69, 'AE', 'CITY', NULL, 'دبي', 'Dubai', NULL, 25.2048, 55.2708),
(70, 'AE', 'CITY', NULL, 'أبوظبي', 'Abu Dhabi', 'ابو ظبي', 24.4539, 54.3773),
(71, 'AE', 'CITY', NULL, 'الشارقة', 'Sharjah', NULL, 25.3463, 55.4209),
(72, 'KW', 'CITY', NULL, 'الكويت', 'Kuwait City', 'kuwait', 29.3759, 47.9774),
(73, 'QA', 'CITY', NULL, 'الدوحة', 'Doha', NULL, 25.2854, 51.531),
(74, 'BH', 'CITY', NULL, 'المنامة', 'Manama', NULL, 26.2285, 50.586),
(75, 'OM', 'CITY', NULL, 'مسقط', 'Muscat', NULL, 23.588, 58.3829),
(76, 'JO', 'CITY', NULL, 'عمان', 'Amman', NULL, 31.9454, 35.9284),
(77, 'LB', 'CITY', NULL, 'بيروت', 'Beirut', NULL, 33.8938, 35.5018),
(78, 'SY', 'CITY', NULL, 'دمشق', 'Damascus', NULL, 33.5138, 36.2765),
(79, 'IQ', 'CITY', NULL, 'بغداد', 'Baghdad', NULL, 33.3152, 44.3661),
(80, 'SD', 'CITY', NULL, 'الخرطوم', 'Khartoum', NULL, 15.5007, 32.5599),
(81, 'LY', 'CITY', NULL, 'طرابلس', 'Tripoli', NULL, 32.8872, 13.1913),
(82, 'TN', 'CITY', NULL, 'تونس', 'Tunis', NULL, 36.8065, 10.1815),
(83, 'DZ', 'CITY', NULL, 'الجزائر', 'Algiers', NULL, 36.7538, 3.0588),
(84, 'MA', 'CITY', NULL, 'الدار البيضاء', 'Casablanca', 'كازابلانكا', 33.5731, -7.5898),
(85, 'MA', 'CITY', NULL, 'الرباط', 'Rabat', NULL, 34.0209, -6.8416),
(86, 'TR', 'CITY', NULL, 'إسطنبول', 'Istanbul', 'اسطنبول', 41.0082, 28.9784),
(87, 'GB', 'CITY', NULL, 'لندن', 'London', NULL, 51.5074, -0.1278),
(88, 'FR', 'CITY', NULL, 'باريس', 'Paris', NULL, 48.8566, 2.3522),
(89, 'DE', 'CITY', NULL, 'برلين', 'Berlin', NULL, 52.52, 13.405),
(90, 'US', 'CITY', NULL, 'نيويورك', 'New York', NULL, 40.7128, -74.006),
(91, 'CA', 'CITY', NULL, 'تورونتو', 'Toronto', NULL, 43.6532, -79.3832);

CREATE TABLE dashboard_search_locations (
    user_id     CHAR(36)     NOT NULL,
    latitude    DECIMAL(7,4) NOT NULL,
    longitude   DECIMAL(8,4) NOT NULL,
    -- DEVICE (shared, fresh, ~1 km grid), CITY or REGION (centre of the place resolved from the profile)
    source      VARCHAR(10)  NOT NULL,
    place_id    INT          NULL,
    updated_at  DATETIME(6)  NOT NULL,
    CONSTRAINT pk_dashboard_search_locations PRIMARY KEY (user_id),
    CONSTRAINT fk_dashboard_search_locations_profile FOREIGN KEY (user_id)
        REFERENCES user_profiles (user_id) ON DELETE CASCADE,
    CONSTRAINT ck_dashboard_search_locations_source CHECK (source IN ('DEVICE', 'CITY', 'REGION'))
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_unicode_ci;

-- radius search: bounding box (latitude range, then longitude), then the exact haversine. Measured against a geohash
-- index on 400 000 positions (60 % in greater Cairo): no faster - the cost is the number of members in the circle,
-- which the candidate pool already bounds.
CREATE INDEX ix_dashboard_search_locations_lat_lon ON dashboard_search_locations (latitude, longitude);

-- device locations are no longer searched directly
DROP INDEX ix_dashboard_locations_lat_lon ON dashboard_member_locations;
ALTER TABLE dashboard_member_locations
    ADD COLUMN changes_day DATE NULL,
    ADD COLUMN changes_count SMALLINT NOT NULL DEFAULT 0;
