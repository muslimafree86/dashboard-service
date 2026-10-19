package com.muslimafree.dashboard.location;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class PlaceNamesTest {

    @ParameterizedTest(name = "{0} = {1}")
    @CsvSource(textBlock = """
            الإسكندرية,          اسكندريه
            الاسكندريه,          اسكندرية
            محافظة الإسكندرية,   الإسكندرية
            الْقَاهِرَة,          القاهرة
            القــاهرة,           القاهرة
            Alexandria Governorate, alexandria
            El Tor,              Tor
            Al-Arish,            arish
            ٦ أكتوبر,            6 اكتوبر
            مدينة نصر,           نصر
            أسيوط,               اسيوط
            بني سويف,            بنى سويف
            """)
    void spellingsMembersTypeMeet(String a, String b) {
        assertThat(PlaceNames.normalize(a)).isEqualTo(PlaceNames.normalize(b)).isNotEmpty();
    }

    @ParameterizedTest
    @CsvSource({"القاهرة, الجيزة", "Maadi, Mokattam"})
    void differentPlacesStayDifferent(String a, String b) {
        assertThat(PlaceNames.normalize(a)).isNotEqualTo(PlaceNames.normalize(b));
    }
}
