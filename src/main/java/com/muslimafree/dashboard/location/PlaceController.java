package com.muslimafree.dashboard.location;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** Place autocomplete for the search bar's city field and for "search near ..." ({@code near} on GET /members). */
@RestController
@RequestMapping("/api/v1/dashboard/places")
@Tag(name = "Places")
public class PlaceController {

    private final PlaceDirectory places;

    public PlaceController(PlaceDirectory places) {
        this.places = places;
    }

    @GetMapping
    @Operation(summary = "Governorates and cities whose Arabic or English name starts with q",
            description = "Matching ignores diacritics, hamza forms, the article (ال / El-) and case: "
                    + "\"اسكندريه\" finds الإسكندرية.")
    public List<PlaceResponse> search(@RequestParam @Size(min = 1, max = 50) String q,
            @RequestParam(required = false) @Pattern(regexp = "[A-Za-z]{2}") String country,
            @RequestParam(defaultValue = "10") @Min(1) @Max(20) int limit) {
        return places.search(q, country, limit).stream().map(PlaceResponse::of).toList();
    }
}
