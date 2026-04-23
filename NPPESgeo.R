# =============================================================================
# NPPES Cardiology Provider Map — NorCal ZIP Code Access Gap Analysis
# =============================================================================
# Outputs a Tableau-ready CSV with provider locations, geocoordinates,
# and ZIP-level socioeconomic data for top 10 richest + poorest NorCal ZIPs
#
# Packages required:
#   install.packages(c("httr", "jsonlite", "tidygeocoder", "tidyverse"))
# Note: No API keys required for any data source in this script
# =============================================================================

install.packages(c("httr", "jsonlite", "tidygeocoder", "tidyverse"))

library(httr)
library(jsonlite)
library(tidygeocoder)
library(tidyverse)

# =============================================================================
# STEP 1: DEFINE YOUR ZIP CODES
# Replace these with your actual top 10 rich / poor NorCal ZIPs
# Median household income (example values — update with your Census ACS data)
# =============================================================================

zip_reference <- tribble(
  ~zip_code, ~zip_name,                          ~county,          ~zip_category, ~zip_median_income, ~population, ~median_age, ~pct_white,
  # --- Richest 10 NorCal ZIPs ---
  # Note: source labels these "Southern CA" but all are NorCal counties — treated as NorCal
  # Note: several incomes are capped at $250,001 (Census top-code) — actual incomes may be higher
  "94027",   "Atherton",                         "San Mateo",      "Rich",        250001,              7720,        48,          67,
  "94957",   "Ross",                             "Marin",          "Rich",        250001,              1858,        46,          90,
  "94024",   "Los Altos",                        "Santa Clara",    "Rich",        250001,              23263,       47,          54,
  "94022",   "Los Altos Hills",                  "Santa Clara",    "Rich",        250001,              20842,       48,          55,
  "94507",   "Alamo",                            "Contra Costa",   "Rich",        250001,              15495,       50,          76,
  "94563",   "Orinda",                           "Contra Costa",   "Rich",        250001,              19496,       47,          69,
  "95030",   "Los Gatos",                        "Santa Clara",    "Rich",        250001,              13585,       49,          73,
  "95120",   "Almaden Valley (San Jose)",         "Santa Clara",    "Rich",        250001,              38341,       46,          48,
  "94028",   "Portola Valley",                   "San Mateo",      "Rich",        250001,              6094,        52,          78,
  "94920",   "Belvedere / Tiburon",              "Marin",          "Rich",        238304,              13010,       50,          81,
  # --- Poorest 10 NorCal ZIPs ---
  # Note: incomes are low relative to NorCal region; some are moderate in absolute terms
  "95652",   "McClellan Park / North Highlands",  "Sacramento",     "Poor",        24866,               944,         32,          43,
  "95202",   "Downtown Stockton",                "San Joaquin",    "Poor",        23954,               6949,        37,          23,
  "94621",   "East Oakland / Coliseum",          "Alameda",        "Poor",        53106,               35816,       32,          NA,
  "94601",   "Fruitvale, Oakland",               "Alameda",        "Poor",        66651,               54166,       34,          14,
  "95824",   "Sacramento / Fruitridge",          "Sacramento",     "Poor",        53938,               31524,       32,          18,
  "95815",   "North Sacramento",                 "Sacramento",     "Poor",        58590,               27838,       34,          34,
  "95825",   "Arden-Arcade, Sacramento",         "Sacramento",     "Poor",        64264,               36081,       34,          45,
  "94607",   "West Oakland",                     "Alameda",        "Poor",        87937,               31067,       37,          27,
  "95811",   "Downtown / Midtown Sacramento",    "Sacramento",     "Poor",        77760,               12463,       36,          50,
  "94612",   "Downtown Oakland",                 "Alameda",        "Poor",        77069,               19773,       37,          32
)

# =============================================================================
# STEP 2: PULL PROVIDERS FROM NPPES API
# Uses the CMS NPPES public API — no API key required
# Taxonomy 207RC0000X = Cardiovascular Disease
# Add more taxonomy codes to the vector to expand search
# =============================================================================

#taxonomy_codes <- c(
  #"207RC0000X",  # Cardiovascular Disease
  #"207RI0011X",  # Interventional Cardiology
  #"207RE0101X"   # Endocrinology, Diabetes & Metabolism
#)

taxonomy_codes <- c(
  "Cardiovascular Disease",
  "Interventional Cardiology", 
  "Endocrinology, Diabetes & Metabolism"
)

pull_nppes <- function(zip, taxonomy_code, limit = 200) {
  
  base_url <- "https://npiregistry.cms.hhs.gov/api/"
  
  response <- GET(
    base_url,
    query = list(
      version              = "2.1",
      taxonomy_description = taxonomy_code,
      postal_code          = zip,
      enumeration_type     = "NPI-1",
      limit                = limit,
      skip                 = 0
    )
  )
  
  if (status_code(response) != 200) {
    message(paste0("API error for ZIP ", zip, ", taxonomy ", taxonomy_code,
                   ": ", status_code(response)))
    return(NULL)
  }
  
  content_data <- content(response, as = "text", encoding = "UTF-8")
  parsed       <- fromJSON(content_data, flatten = TRUE)
  
  if (is.null(parsed$results) || length(parsed$results) == 0) {
    message(paste0("No results for ZIP ", zip, ", taxonomy ", taxonomy_code))
    return(NULL)
  }
  
  results <- parsed$results
  
  # Helper to safely extract a field that may not exist
  safe_field <- function(x) if (is.null(x)) NA_character_ else as.character(x)
  
  providers <- tibble(
    npi                   = safe_field(results$number),
    provider_last_name    = safe_field(results$basic.last_name),
    provider_first_name   = safe_field(results$basic.first_name),
    credential            = safe_field(results$basic.credential),
    gender                = safe_field(results$basic.gender),
    practice_address = map_chr(results$addresses, ~ {
      addr <- .x[.x$address_purpose == "LOCATION", ]
      if (nrow(addr) == 0) NA_character_ else as.character(addr$address_1[1])
    }),
    city = map_chr(results$addresses, ~ {
      addr <- .x[.x$address_purpose == "LOCATION", ]
      if (nrow(addr) == 0) NA_character_ else as.character(addr$city[1])
    }),
    state = map_chr(results$addresses, ~ {
      addr <- .x[.x$address_purpose == "LOCATION", ]
      if (nrow(addr) == 0) NA_character_ else as.character(addr$state[1])
    }),
    provider_zip = map_chr(results$addresses, ~ {
      addr <- .x[.x$address_purpose == "LOCATION", ]
      if (nrow(addr) == 0) NA_character_ else substr(as.character(addr$postal_code[1]), 1, 5)
    }),
    phone = map_chr(results$addresses, ~ {
      addr <- .x[.x$address_purpose == "LOCATION", ]
      if (nrow(addr) == 0) NA_character_ else as.character(addr$telephone_number[1])
    }),
    primary_taxonomy_code = map_chr(results$taxonomies, ~ {
      tax <- .x[.x$primary == TRUE, ]
      if (nrow(tax) == 0) NA_character_ else as.character(tax$code[1])
    }),
    taxonomy_descriptor = map_chr(results$taxonomies, ~ {
      tax <- .x[.x$primary == TRUE, ]
      if (nrow(tax) == 0) NA_character_ else as.character(tax$desc[1])
    }),
    search_zip      = zip,
    search_taxonomy = taxonomy_code
  )
  
  return(providers)
}

# Null coalescing operator helper
`%||%` <- function(a, b) if (!is.null(a)) a else b

# --- Run the pull across all ZIPs and taxonomies ---
message("Pulling NPPES data... this may take a few minutes.")

all_providers <- map2_dfr(
  rep(zip_reference$zip_code, each = length(taxonomy_codes)),
  rep(taxonomy_codes, times = nrow(zip_reference)),
  ~ {
    Sys.sleep(0.3)  # Be polite to the API — avoid rate limiting
    pull_nppes(.x, .y)
  }
)

# Deduplicate — same provider may appear across multiple taxonomy/ZIP searches
all_providers <- all_providers %>%
  distinct(npi, .keep_all = TRUE) %>%
  filter(state == "CA")  # California only
providers_unique <- all_providers %>% distinct(npi, .keep_all = TRUE)

message(glue::glue("Found {nrow(all_providers)} unique providers across all ZIPs."))

# =============================================================================
# STEP 3: GEOCODE PROVIDER ADDRESSES
# Uses tidygeocoder with the Census geocoder (free, no API key)
# Falls back to OSM/Nominatim if Census returns no result
# =============================================================================

message("Geocoding provider addresses...")

# Build full address string for geocoding
providers_to_geocode <- providers_unique %>%
  mutate(
    full_address = paste(practice_address, city, state, provider_zip, "USA",
                         sep = ", ")
  )

# First add a row ID to track which rows need OSM fallback
providers_unique <- providers_unique %>% 
  mutate(row_id = row_number())

# Census geocode
geocoded <- tidygeocoder::geocode(
  providers_unique,
  street  = practice_address,
  city    = city,
  state   = state,
  method  = "census"
)

# Fill failed rows with OSM
failed <- geocoded %>% filter(is.na(lat))

if (nrow(failed) > 0) {
  osm_results <- tidygeocoder::geocode(
    failed %>% select(-lat, -long),
    street  = practice_address,
    city    = city,
    state   = state,
    method  = "osm"
  )
  geocoded <- bind_rows(
    geocoded %>% filter(!is.na(lat)),
    osm_results
  )
}

n_geocoded   <- sum(!is.na(geocoded$lat))
n_failed     <- sum(is.na(geocoded$lat))
message(glue::glue("Geocoded: {n_geocoded} succeeded, {n_failed} failed."))

# =============================================================================
# STEP 4: JOIN ZIP REFERENCE DATA
# Attaches income tier, ADI score, and category to each provider
# Joins on the search ZIP (the ZIP you searched in NPPES),
# not necessarily where the provider's office is located
# =============================================================================

providers_final <- geocoded %>%
  left_join(
    zip_reference %>% select(zip_code, zip_name, county, zip_category,
                             zip_median_income),
    by = c("search_zip" = "zip_code")
  ) %>%
  # Clean up and select final columns in Tableau-friendly order
  select(
    npi,
    provider_last_name,
    provider_first_name,
    credential,
    gender,
    primary_taxonomy_code,
    taxonomy_descriptor,
    practice_address,
    city,
    state,
    provider_zip,
    phone,
    lat,
    long,
    search_zip,
    zip_name,
    county,
    zip_category,
    zip_median_income
    
  ) %>%
  # Ensure ZIP codes are character strings (not numeric — Tableau needs this)
  mutate(
    provider_zip = as.character(provider_zip),
    search_zip   = as.character(search_zip)
  )

# =============================================================================
# STEP 5: BUILD ZIP-LEVEL SUMMARY TABLE
# Calculates specialist count and patients-per-specialist ratio per ZIP
# This is your second Tableau data source for the choropleth layer
# =============================================================================

# PREVALENCE_RATE is now a fallback only — Step 4b pulls real CDC PLACES
# high cholesterol prevalence by ZIP. This constant is used only if the
# CDC API call fails for a given ZIP.
PREVALENCE_RATE_FALLBACK <- 0.32  # ~32% national adult high cholesterol rate (CDC)
ADULT_FRACTION           <- 0.78  # Share of ZIP population that is adult

# =============================================================================
# STEP 5a: PULL CDC PLACES HIGH CHOLESTEROL PREVALENCE BY ZCTA
# Source: CDC PLACES via Socrata API on chronicdata.cdc.gov
# Dataset: PLACES ZCTA Data (GIS Friendly Format) — latest release
# Field: HIGHCHOL_CrudePrev = crude prevalence % of high cholesterol among adults
# No API key required; passing app token is optional but recommended for rate limits
# Get a free app token at: https://data.cdc.gov/profile/app_tokens
# =============================================================================

# ── Step 5a: CDC PLACES cholesterol prevalence ────────────────────────────────

# Pull CA high cholesterol by county from PLACES
places_url <- paste0(
  "https://data.cdc.gov/resource/swc5-untb.json?",
  "stateabbr=CA&measureid=HIGHCHOL&datavaluetypeid=CrdPrv&$limit=100"
)

places_response <- httr::GET(places_url)
places_data <- httr::content(places_response, as = "text") %>%
  jsonlite::fromJSON() %>%
  select(locationname, data_value) %>%
  mutate(
    county_clean = toupper(trimws(locationname)),
    highchol_crude_prev_pct = as.numeric(data_value)
  ) %>%
  select(county_clean, highchol_crude_prev_pct)

# Build zip_summary from providers_final
zip_summary <- providers_final %>%
  group_by(provider_zip) %>%
  summarise(
    specialist_count = n_distinct(npi),
    .groups = "drop"
  ) %>%
  rename(zip = provider_zip) %>%
  left_join(
    zip_reference %>% select(zip_code, zip_name, county, zip_category, zip_median_income, population),
    by = c("zip" = "zip_code")
  ) %>%
  mutate(county_clean = toupper(trimws(county))) %>%
  left_join(places_data, by = "county_clean") %>%
  mutate(
    estimated_highchol_patients = round(population * ADULT_FRACTION * (highchol_crude_prev_pct / 100)),
    patients_per_specialist = round(estimated_highchol_patients / specialist_count),
    access_gap_flag = case_when(
      specialist_count == 0            ~ "No Specialists",
      patients_per_specialist > 1000   ~ "Critical Gap",
      patients_per_specialist > 500    ~ "High Gap",
      patients_per_specialist > 200    ~ "Moderate Gap",
      TRUE                             ~ "Adequate Access"
    )
  )

glimpse(zip_summary)

# =============================================================================
# STEP 5b: PULL AREA DEPRIVATION INDEX (ADI) BY ZIP
# Source: University of Wisconsin CHESS / Neighborhood Atlas
# The ADI API requires free registration at:
#   https://www.neighborhoodatlas.medicine.wisc.edu/
#
# TWO OPTIONS — uncomment whichever applies to you:
#   Option A: Manual lookup  (no registration needed, fine for 20 ZIPs)
#   Option B: Direct CSV download from Neighborhood Atlas (after login)
# =============================================================================

# --- OPTION A: Paste ADI values manually after looking up at ---
# --- https://www.neighborhoodatlas.medicine.wisc.edu/        ---
# National ADI percentile: 1 = least deprived, 100 = most deprived
# These are approximate values — replace with exact values from the site

adi_manual <- tribble(
  ~zip_code, ~adi_natl_rank,
  # Rich ZIPs — low ADI (least deprived)
  "94027",   3,
  "94957",   2,
  "94024",   4,
  "94022",   4,
  "94507",   5,
  "94563",   6,
  "95030",   5,
  "95120",   7,
  "94028",   3,
  "94920",   4,
  # Poor ZIPs — high ADI (most deprived)
  "95652",   91,
  "95202",   95,
  "94621",   88,
  "94601",   82,
  "95824",   79,
  "95815",   80,
  "95825",   72,
  "94607",   68,
  "95811",   55,
  "94612",   65
)

# --- OPTION B: Load from downloaded CSV (comment out Option A above) ---
# After downloading from Neighborhood Atlas, the file has columns:
#   ZIPCODE, ADI_NATL (national rank), ADI_STATE (state rank)
# Uncomment and update the path:
#

 adi_manual <- read_csv("~/Downloads/adi-download/CA_2023_ADI_9 Digit Zip Code_v4_0_1.csv") %>%
   mutate(zip_code = substr(as.character(ZIP_4), 1, 5)) %>%
   select(zip_code, adi_natl_rank = ADI_NATRANK) %>%
   mutate(adi_natl_rank = as.numeric(adi_natl_rank)) %>%
   group_by(zip_code) %>%
   summarise(adi_natl_rank = mean(adi_natl_rank, na.rm = TRUE)) %>%
   ungroup()

# =============================================================================
# STEP 5c: BUILD FINAL ZIP-LEVEL SUMMARY TABLE
# Joins specialist counts + CDC PLACES cholesterol + ADI + your source data
# =============================================================================

 message("Building ZIP-level summary table...")
 zip_summary <- providers_final %>%
   group_by(search_zip, zip_name, county, zip_category, zip_median_income) %>%
   summarise(
     specialist_count = n_distinct(npi),
     .groups = "drop"
   ) %>%
   right_join(
     zip_reference %>% select(zip_code, zip_name, county, zip_category,
                              zip_median_income, population, median_age, pct_white),
     by = c("search_zip" = "zip_code",
            "zip_name", "county", "zip_category", "zip_median_income")
   ) %>%
   mutate(county_clean = toupper(trimws(county))) %>%
   left_join(places_data, by = "county_clean") %>%
   left_join(adi_manual, by = c("search_zip" = "zip_code")) %>%
   mutate(
     adi_natl_rank = as.numeric(adi_natl_rank),
     specialist_count = replace_na(specialist_count, 0),
     effective_prevalence_rate = coalesce(highchol_crude_prev_pct / 100, PREVALENCE_RATE_FALLBACK),
     estimated_adults = round(population * ADULT_FRACTION),
     estimated_highchol_patients = round(estimated_adults * effective_prevalence_rate),
     patients_per_specialist = case_when(
       specialist_count == 0 ~ NA_real_,
       TRUE ~ round(estimated_highchol_patients / specialist_count)
     ),
     access_gap_flag = case_when(
       specialist_count == 0          ~ "No Specialists",
       patients_per_specialist > 1000 ~ "Critical Gap",
       patients_per_specialist > 500  ~ "High Gap",
       patients_per_specialist > 200  ~ "Moderate Gap",
       TRUE                           ~ "Adequate Access"
     ),
     income_disparity_index = round(zip_median_income / 85000, 2),
     priority_score = round(
       0.40 * case_when(
         specialist_count == 0        ~ 100,  # No specialists = maximum access gap
         is.na(patients_per_specialist) ~ 50, # Unknown = use midpoint
         TRUE ~ pmin(patients_per_specialist / 20, 100)
       ) +
         0.35 * replace_na(adi_natl_rank, 50) +
         0.25 * replace_na(highchol_crude_prev_pct, 32),
       1
     
     )
   ) %>%
   arrange(desc(priority_score))
 
# =============================================================================
# STEP 6: EXPORT TABLEAU-READY CSVs
# =============================================================================

output_dir <- "."   # Change to your preferred output path

write_csv(providers_final,
          file.path(output_dir, "providers_tableau.csv"),
          na = "")

write_csv(zip_summary,
          file.path(output_dir, "zip_summary_tableau.csv"),
          na = "")

# Print top priority ZIPs to console as a quick sanity check
message("\n=== Top 5 Priority ZIPs by composite score ===")
zip_summary %>%
  select(search_zip, zip_name, zip_category, highchol_crude_prev_pct,
         specialist_count, patients_per_specialist, adi_natl_rank, priority_score) %>%
  head(5) %>%
  print()

message("
=== Export complete ===
Files written:
  providers_tableau.csv   — one row per provider, lat/long + ZIP socioeconomic data
  zip_summary_tableau.csv — one row per ZIP, with:
    - CDC PLACES high cholesterol prevalence (%)
    - High blood pressure + diabetes prevalence (context)
    - ADI national rank (deprivation)
    - Estimated high-cholesterol patients
    - Patients-per-specialist ratio
    - Access gap flag
    - Composite priority score

Data sources used:
  - NPPES NPI Registry (provider locations)
  - CDC PLACES via Socrata API (disease prevalence)
  - Area Deprivation Index / Neighborhood Atlas (socioeconomic deprivation)
  - Your source data (population, income, demographics)

Next steps in Tableau:
  1. Connect to providers_tableau.csv
     -> Latitude/Longitude as Dimensions for dot placement
     -> Color by zip_category or taxonomy_descriptor
  2. Connect to zip_summary_tableau.csv as second source
     -> Join on zip_code
     -> Choropleth: shade by highchol_crude_prev_pct or priority_score
     -> Size marks by patients_per_specialist
  3. Use access_gap_flag for dashboard filter / color legend
")

# =============================================================================
# OPTIONAL: Quick ggplot2 preview before opening Tableau
# =============================================================================

install.packages(c("leaflet", "leaflet.extras", "sf", "tigris"))

library(leaflet)
#library(leaflet.extras)
library(sf)

library(tigris)

ca_zips <- tigris::zctas(year = 2020) %>%
  filter(ZCTA5CE20 %in% zip_summary$search_zip) %>%
  sf::st_transform(crs = 4326)

options(tigris_use_cache = TRUE)

# Pull ZIP shapefiles for California


# Merge with your summary data
ca_zips <- ca_zips %>%
  left_join(zip_summary, by = c("ZCTA5CE20" = "search_zip"))

# Color palette for priority score
pal_priority <- colorNumeric(
  palette = "YlOrRd",
  domain  = ca_zips$priority_score,
  na.color = "#cccccc"
)

# Color palette for access gap flag
pal_gap <- colorFactor(
  palette = c("green", "yellow", "orange", "red", "darkred"),
  levels  = c("Adequate Access", "Moderate Gap", "High Gap", "Critical Gap", "No Specialists")
)

# Build map
map <- leaflet() %>%
  addProviderTiles(providers$CartoDB.Positron) %>%
  
  # ZIP polygons shaded by priority score
  addPolygons(
    data        = ca_zips,
    fillColor   = ~pal_priority(priority_score),
    fillOpacity = 0.6,
    color       = "white",
    weight      = 1,
    popup = ~paste0(
      "<b>ZIP: ", ZCTA5CE20, " — ", zip_name, "</b><br>",
      "Category: ", zip_category, "<br>",
      "Priority Score: ", priority_score, "<br>",
      "Specialists: ", specialist_count, "<br>",
      "Est. High Cholesterol Patients: ", format(estimated_highchol_patients, big.mark = ","), "<br>",
      "Patients per Specialist: ", patients_per_specialist, "<br>",
      "Access Gap: ", access_gap_flag, "<br>",
      "ADI National Rank: ", adi_natl_rank, "<br>",
      "Cholesterol Prevalence: ", highchol_crude_prev_pct, "%"
    ),
    group = "Priority Score"
  ) %>%
  
  # Provider dots
  addCircleMarkers(
    data        = providers_final %>% filter(!is.na(lat)),
    lng         = ~long,
    lat         = ~lat,
    radius      = 5,
    color       = "navy",
    fillOpacity = 0.8,
    stroke      = FALSE,
    popup = ~paste0(
      "<b>", provider_first_name, " ", provider_last_name, ", ", credential, "</b><br>",
      taxonomy_descriptor, "<br>",
      practice_address, ", ", city, " ", provider_zip, "<br>",
      "Phone: ", phone
    ),
    group = "Providers"
  ) %>%
  
  # Legend
  # After your addPolygons / addCircleMarkers calls, add this:
  map <- map %>%
  addLegend(
    position = "bottomright",
    pal      = pal_priority,
    values   = ca_zips$priority_score,
    title    = "Priority Score",
    opacity  = 0.7
  ) %>%
  addLayersControl(
    overlayGroups = c("Priority Score", "Providers"),
    options = layersControlOptions(collapsed = FALSE)
  )
library(htmlwidgets)

# 1. Build the map normally — pipe chain ends here
map <- leaflet() %>%
  addProviderTiles(providers$CartoDB.Positron) %>%
  addPolygons(
    data        = ca_zips,
    layerId     = ~ZCTA5CE20,
    fillColor   = ~pal_priority(priority_score),
    fillOpacity = 0.6,
    color       = "white",
    weight      = 1,
    popup = ~paste0(
      "<b>ZIP: ", ZCTA5CE20, " — ", zip_name, "</b><br>",
      "Category: ", zip_category, "<br>",
      "Priority Score: ", round(priority_score, 1), "<br>",
      "Specialists: ", specialist_count, "<br>",
      "Est. High Cholesterol Patients: ", format(estimated_highchol_patients, big.mark = ","), "<br>",
      "Patients per Specialist: ", patients_per_specialist, "<br>",
      "Access Gap: ", access_gap_flag, "<br>",
      "ADI National Rank: ", adi_natl_rank, "<br>",
      "Cholesterol Prevalence: ", highchol_crude_prev_pct, "%"
    ),
    group = "Priority Score"
  ) %>%
  addCircleMarkers(
    data        = providers_final %>% filter(!is.na(lat)),
    lng         = ~long,
    lat         = ~lat,
    radius      = 5,
    color       = "navy",
    fillOpacity = 0.8,
    stroke      = FALSE,
    popup = ~paste0(
      "<b>", provider_first_name, " ", provider_last_name, ", ", credential, "</b><br>",
      taxonomy_descriptor, "<br>",
      practice_address, ", ", city, " ", provider_zip, "<br>",
      "Phone: ", phone
    ),
    group = "Providers"
  ) %>%
  addLegend(
    position = "bottomright",
    pal      = pal_priority,
    values   = ca_zips$priority_score,
    title    = "Priority Score",
    opacity  = 0.7
  ) %>%
  addLayersControl(
    overlayGroups = c("Priority Score", "Providers"),
    options = layersControlOptions(collapsed = FALSE)
  )

# 2. Write JS to file — OUTSIDE the pipe chain
js <- "function(el, x) {
  var ZIP_DATA = {
    '95652': { name:'McClellan Park / North Highlands', adi:57.0, highchol:33.8, specialist_count:0, patients_per_specialist:null, access_gap:'No Specialists' },
    '94957': { name:'Ross', adi:null, highchol:37.3, specialist_count:0, patients_per_specialist:null, access_gap:'No Specialists' },
    '95824': { name:'Sacramento / Fruitridge', adi:48.2, highchol:33.8, specialist_count:0, patients_per_specialist:null, access_gap:'No Specialists' },
    '95815': { name:'North Sacramento', adi:45.2, highchol:33.8, specialist_count:1, patients_per_specialist:7339, access_gap:'Critical Gap' },
    '94621': { name:'East Oakland / Coliseum', adi:24.6, highchol:33.4, specialist_count:2, patients_per_specialist:4666, access_gap:'Critical Gap' },
    '94601': { name:'Fruitvale Oakland', adi:15.7, highchol:33.4, specialist_count:0, patients_per_specialist:null, access_gap:'No Specialists' },
    '95811': { name:'Downtown Sacramento', adi:14.0, highchol:33.8, specialist_count:1, patients_per_specialist:3286, access_gap:'Critical Gap' },
    '94607': { name:'West Oakland', adi:12.3, highchol:33.4, specialist_count:1, patients_per_specialist:8093, access_gap:'Critical Gap' },
    '94027': { name:'Atherton', adi:1.1, highchol:37.4, specialist_count:1, patients_per_specialist:2252, access_gap:'Critical Gap' },
    '94507': { name:'Alamo', adi:1.0, highchol:36.1, specialist_count:2, patients_per_specialist:2182, access_gap:'Critical Gap' },
    '95120': { name:'Almaden Valley', adi:1.2, highchol:34.2, specialist_count:1, patients_per_specialist:10228, access_gap:'Critical Gap' },
    '94024': { name:'Los Altos', adi:1.0, highchol:34.2, specialist_count:2, patients_per_specialist:3103, access_gap:'Critical Gap' },
    '95202': { name:'Downtown Stockton', adi:41.3, highchol:33.7, specialist_count:2, patients_per_specialist:914, access_gap:'High Gap' },
    '94920': { name:'Belvedere / Tiburon', adi:1.0, highchol:37.3, specialist_count:3, patients_per_specialist:1262, access_gap:'Critical Gap' },
    '95030': { name:'Los Gatos', adi:1.1, highchol:34.2, specialist_count:3, patients_per_specialist:1208, access_gap:'Critical Gap' },
    '94563': { name:'Orinda', adi:1.1, highchol:36.1, specialist_count:6, patients_per_specialist:915, access_gap:'High Gap' },
    '95825': { name:'Arden-Arcade Sacramento', adi:38.9, highchol:33.8, specialist_count:37, patients_per_specialist:257, access_gap:'Moderate Gap' },
    '94028': { name:'Portola Valley', adi:1.0, highchol:37.4, specialist_count:3, patients_per_specialist:593, access_gap:'High Gap' },
    '94612': { name:'Downtown Oakland', adi:10.4, highchol:33.4, specialist_count:107, patients_per_specialist:48, access_gap:'Adequate Access' },
    '94022': { name:'Los Altos Hills', adi:1.0, highchol:34.2, specialist_count:88, patients_per_specialist:63, access_gap:'Adequate Access' }
  };
  function accessScore(d) {
    if (d.specialist_count === 0) return 100;
    if (d.patients_per_specialist == null) return 50;
    return Math.min(d.patients_per_specialist / 20, 100);
  }
  function priorityScore(d, w) {
    var acc = accessScore(d);
    var adi = d.adi != null ? d.adi : 50;
    var chol = d.highchol != null ? d.highchol : 32;
    return (w.access/100)*acc + (w.adi/100)*adi + (w.chol/100)*chol;
  }
  var YLORD = [[1,1,0.8],[1,0.929,0.627],[0.996,0.851,0.463],[0.992,0.698,0.384],[0.988,0.553,0.349],[0.988,0.306,0.165],[0.890,0.102,0.110],[0.741,0,0.149]];
  function lerpColor(t) {
    t = Math.max(0, Math.min(1, t));
    var n = YLORD.length - 1;
    var i = Math.min(Math.floor(t * n), n - 1);
    var f = t * n - i;
    var a = YLORD[i], b = YLORD[i+1];
    return 'rgb(' + [0,1,2].map(function(c){ return Math.round((a[c]+(b[c]-a[c])*f)*255); }).join(',') + ')';
  }
  function scoreColor(score, mn, mx) {
    if (score == null || isNaN(score)) return '#cccccc';
    return lerpColor(mx > mn ? (score-mn)/(mx-mn) : 0.5);
  }
  var polyLayers = [];
  this.eachLayer(function(layer) {
    if (typeof layer.eachLayer === 'function') {
      layer.eachLayer(function(poly) {
        var zip = poly.options && poly.options.layerId;
        if (zip && ZIP_DATA[zip]) polyLayers.push({ layer: poly, zip: zip });
      });
    }
  });
  var W = { access:40, adi:35, chol:25 };
  function recolorMap() {
    var scores = polyLayers.map(function(p){ return priorityScore(ZIP_DATA[p.zip], W); });
    var mn = Math.min.apply(null, scores);
    var mx = Math.max.apply(null, scores);
    polyLayers.forEach(function(p, i) {
      var s = scores[i];
      var d = ZIP_DATA[p.zip];
      p.layer.setStyle({ fillColor: scoreColor(s, mn, mx) });
      p.layer.bindPopup('<b>ZIP: ' + p.zip + ' - ' + d.name + '</b><br>Priority Score: <b>' + s.toFixed(1) + '</b><br>Access Gap: ' + d.access_gap + '<br>ADI: ' + (d.adi != null ? d.adi : 'N/A') + '<br>Cholesterol: ' + d.highchol + '%<br>Specialists: ' + d.specialist_count);
    });
  }
  var panel = document.createElement('div');
  panel.style.cssText = 'position:absolute;top:10px;right:10px;z-index:1000;background:#0f172a;border:1px solid #1e293b;border-radius:10px;padding:18px;width:260px;box-shadow:0 4px 24px rgba(0,0,0,.5);color:#e2e8f0;font-family:sans-serif;';
  panel.innerHTML = '<div style=\"margin-bottom:14px;font-family:monospace;font-size:10px;letter-spacing:.15em;text-transform:uppercase;color:#888\">Priority Weights</div>' +
    '<div style=\"margin-bottom:12px\"><div style=\"display:flex;justify-content:space-between\"><span style=\"font-size:12px;color:#ddd\">Access Gap</span><span id=\"vv-access\" style=\"font-family:monospace;font-size:16px;font-weight:700;color:#f97316\">40%</span></div><div style=\"position:relative;height:5px;border-radius:3px;background:#333;margin-top:5px\"><div id=\"ff-access\" style=\"position:absolute;left:0;top:0;height:100%;width:40%;border-radius:3px;background:#f97316\"></div><input id=\"sl-access\" type=\"range\" min=\"0\" max=\"100\" value=\"40\" style=\"position:absolute;top:50%;left:0;transform:translateY(-50%);width:100%;opacity:0;cursor:pointer;height:18px;margin:0\"></div></div>' +
    '<div style=\"margin-bottom:12px\"><div style=\"display:flex;justify-content:space-between\"><span style=\"font-size:12px;color:#ddd\">Deprivation (ADI)</span><span id=\"vv-adi\" style=\"font-family:monospace;font-size:16px;font-weight:700;color:#a78bfa\">35%</span></div><div style=\"position:relative;height:5px;border-radius:3px;background:#333;margin-top:5px\"><div id=\"ff-adi\" style=\"position:absolute;left:0;top:0;height:100%;width:35%;border-radius:3px;background:#a78bfa\"></div><input id=\"sl-adi\" type=\"range\" min=\"0\" max=\"100\" value=\"35\" style=\"position:absolute;top:50%;left:0;transform:translateY(-50%);width:100%;opacity:0;cursor:pointer;height:18px;margin:0\"></div></div>' +
    '<div style=\"margin-bottom:12px\"><div style=\"display:flex;justify-content:space-between\"><span style=\"font-size:12px;color:#ddd\">Cholesterol Burden</span><span id=\"vv-chol\" style=\"font-family:monospace;font-size:16px;font-weight:700;color:#34d399\">25%</span></div><div style=\"position:relative;height:5px;border-radius:3px;background:#333;margin-top:5px\"><div id=\"ff-chol\" style=\"position:absolute;left:0;top:0;height:100%;width:25%;border-radius:3px;background:#34d399\"></div><input id=\"sl-chol\" type=\"range\" min=\"0\" max=\"100\" value=\"25\" style=\"position:absolute;top:50%;left:0;transform:translateY(-50%);width:100%;opacity:0;cursor:pointer;height:18px;margin:0\"></div></div>' +
    '<div id=\"total-bar\" style=\"display:flex;justify-content:space-between;padding:8px 12px;border-radius:7px;background:#0a2a1a;border:1px solid #166534\"><span id=\"total-lbl\" style=\"font-family:monospace;font-size:11px;color:#4ade80\">Total</span><span id=\"total-num\" style=\"font-family:monospace;font-size:15px;font-weight:700;color:#4ade80\">100%</span></div>' +
    '<button id=\"btn-reset\" style=\"width:100%;margin-top:10px;padding:8px;background:transparent;border:1px solid #444;border-radius:7px;color:#888;font-size:10px;font-family:monospace;cursor:pointer\">Reset (40 / 35 / 25)</button>';
  el.style.position = 'relative';
  el.appendChild(panel);
  function updateUI() {
    ['access','adi','chol'].forEach(function(k) {
      document.getElementById('ff-'+k).style.width = W[k]+'%';
      document.getElementById('vv-'+k).textContent = W[k]+'%';
      document.getElementById('sl-'+k).value = W[k];
    });
    var total = W.access + W.adi + W.chol;
    var valid = total === 100;
    document.getElementById('total-num').textContent = total+'%';
    var tb = document.getElementById('total-bar');
    tb.style.background = valid ? '#0a2a1a' : '#2a0a0a';
    tb.style.borderColor = valid ? '#166534' : '#7f1d1d';
    document.getElementById('total-num').style.color = valid ? '#4ade80' : '#f87171';
    document.getElementById('total-lbl').style.color = valid ? '#4ade80' : '#f87171';
    if (valid) recolorMap();
  }
  function adjustWeight(key, newVal) {
    var others = Object.keys(W).filter(function(k){ return k !== key; });
    var remaining = 100 - newVal;
    var otherTotal = others.reduce(function(s,k){ return s+W[k]; }, 0);
    var updated = { access:W.access, adi:W.adi, chol:W.chol };
    updated[key] = newVal;
    if (otherTotal === 0) {
      others.forEach(function(k){ updated[k] = Math.round(remaining/others.length); });
    } else {
      others.forEach(function(k){ updated[k] = Math.max(0, Math.round((W[k]/otherTotal)*remaining)); });
    }
    var diff = 100 - Object.values(updated).reduce(function(a,b){ return a+b; }, 0);
    if (diff !== 0) {
      var adj = others.reduce(function(a,b){ return updated[a]>updated[b]?a:b; });
      updated[adj] = Math.max(0, updated[adj]+diff);
    }
    W = updated;
    updateUI();
  }
  ['access','adi','chol'].forEach(function(k) {
    document.getElementById('sl-'+k).addEventListener('input', function(e) {
      adjustWeight(k, Number(e.target.value));
    });
  });
  document.getElementById('btn-reset').addEventListener('click', function() {
    W = { access:40, adi:35, chol:25 };
    updateUI();
  });
}"

map <- leaflet() %>%
  addProviderTiles(providers$CartoDB.Positron) %>%
  addPolygons(
    data        = ca_zips,
    layerId     = ~ZCTA5CE20,
    fillColor   = ~pal_priority(priority_score),
    fillOpacity = 0.6,
    color       = "white",
    weight      = 1,
    popup = ~paste0(
      "<b>ZIP: ", ZCTA5CE20, " — ", zip_name, "</b><br>",
      "Category: ", zip_category, "<br>",
      "Priority Score: ", round(priority_score, 1), "<br>",
      "Specialists: ", specialist_count, "<br>",
      "Est. High Cholesterol Patients: ", format(estimated_highchol_patients, big.mark = ","), "<br>",
      "Patients per Specialist: ", patients_per_specialist, "<br>",
      "Access Gap: ", access_gap_flag, "<br>",
      "ADI National Rank: ", adi_natl_rank, "<br>",
      "Cholesterol Prevalence: ", highchol_crude_prev_pct, "%"
    ),
    group = "Priority Score"
  ) %>%
  addCircleMarkers(
    data        = providers_final %>% filter(!is.na(lat)),
    lng         = ~long,
    lat         = ~lat,
    radius      = 5,
    color       = "navy",
    fillOpacity = 0.8,
    stroke      = FALSE,
    popup = ~paste0(
      "<b>", provider_first_name, " ", provider_last_name, ", ", credential, "</b><br>",
      taxonomy_descriptor, "<br>",
      practice_address, ", ", city, " ", provider_zip, "<br>",
      "Phone: ", phone
    ),
    group = "Providers"
  ) %>%
  addLegend(
    position = "bottomright",
    pal      = pal_priority,
    values   = ca_zips$priority_score,
    title    = "Priority Score",
    opacity  = 0.7
  ) %>%
  addLayersControl(
    overlayGroups = c("Priority Score", "Providers"),
    options = layersControlOptions(collapsed = FALSE)
  ) %>%
  onRender(js)

map

# 3. Attach the JS to the map
map <- map %>% onRender(readLines(js_file) |> paste(collapse = "\n"))

# 4. Display
map

#save html
library(htmlwidgets)
install.packages("rsconnect")
library(rsconnect)
saveWidget(map, file = "norCal_access_map_slider.html", selfcontained = TRUE)

#see priority scoring
zip_summary %>% 
  select(search_zip, zip_name, zip_category, specialist_count, 
         patients_per_specialist, adi_natl_rank, highchol_crude_prev_pct, 
         priority_score, access_gap_flag) %>%
  arrange(desc(priority_score))

#save data
save(
  providers_final,
  zip_summary,
  zip_reference,
  ca_zips,
  map,
  ADULT_FRACTION,
  PREVALENCE_RATE_FALLBACK,
  file = "norCal_access_workspace.RData"
)