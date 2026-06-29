import pandas as pd
from scipy import stats
import plotly.graph_objects as go
from plotly.subplots import make_subplots

FILES = {
    "6mo (2026-05-25)": "astrocyte_counts_2026_05_25.csv",
    "11mo (2026-05-29)": "astrocyte_counts_2026_05_29.csv",
}

GENOTYPE_MAP = {**{i: "WT" for i in range(1, 7)}, **{i: "KO" for i in range(7, 13)}}
GENOTYPE_COLORS = {"WT": "#4C72B0", "KO": "#DD8452"}

def load(age, path):
    df = pd.read_csv(path, usecols=["Image", "Astrocytes_per_mm2"])
    df = df.dropna(subset=["Astrocytes_per_mm2"])
    df = df.drop_duplicates()
    df["Animal"] = df["Image"].str.extract(r"RecognizedCode_(\d+)", expand=False).astype(int)
    df["Genotype"] = df["Animal"].map(GENOTYPE_MAP)
    df["Age"] = age
    return df

data = pd.concat([load(age, path) for age, path in FILES.items()], ignore_index=True)

# Per-animal means
animal_means = (
    data.groupby(["Age", "Animal", "Genotype"])["Astrocytes_per_mm2"]
    .mean()
    .reset_index()
    .rename(columns={"Astrocytes_per_mm2": "Mean_per_mm2"})
)

# Summary table
summary = (
    data.groupby(["Age", "Genotype"])["Astrocytes_per_mm2"]
    .agg(Mean="mean", SD="std", N_scenes="count")
    .round(2)
    .reset_index()
)

# Welch's t-test (using per-scene values)
print("=" * 60)
print("Per-animal means (used for table; t-test uses per-scene values)")
print("=" * 60)
print(animal_means.to_string(index=False))

print("\n" + "=" * 60)
print("Summary: Mean ± SD astrocytes/mm² (N = scenes)")
print("=" * 60)
print(summary.to_string(index=False))

print("\n" + "=" * 60)
print("Welch's t-test (per-scene values, WT vs KO within each age)")
print("=" * 60)
for age, grp in data.groupby("Age"):
    wt = grp.loc[grp["Genotype"] == "WT", "Astrocytes_per_mm2"]
    ko = grp.loc[grp["Genotype"] == "KO", "Astrocytes_per_mm2"]
    t, p = stats.ttest_ind(wt, ko, equal_var=False)
    sig = "***" if p < 0.001 else "**" if p < 0.01 else "*" if p < 0.05 else "ns"
    print(f"{age}: t = {t:.3f}, p = {p:.4f}  {sig}")

# --- Plot: one subplot per age group ---
ages = list(FILES.keys())
fig = make_subplots(rows=1, cols=2, subplot_titles=ages, shared_yaxes=True)

for col, age in enumerate(ages, start=1):
    age_data = data[data["Age"] == age]
    age_animals = animal_means[animal_means["Age"] == age]

    for geno in ["WT", "KO"]:
        color = GENOTYPE_COLORS[geno]
        gd = age_animals[age_animals["Genotype"] == geno]
        pts = age_data[age_data["Genotype"] == geno]

        # bar = mean of animal means ± SD of animal means
        grand_mean = gd["Mean_per_mm2"].mean()
        grand_sd = gd["Mean_per_mm2"].std()

        fig.add_trace(go.Bar(
            x=[geno],
            y=[grand_mean],
            error_y=dict(type="data", array=[grand_sd], visible=True),
            name=geno,
            marker_color=color,
            showlegend=(col == 1),
            legendgroup=geno,
        ), row=1, col=col)

        # individual animal means as points
        fig.add_trace(go.Scatter(
            x=[geno] * len(gd),
            y=gd["Mean_per_mm2"].tolist(),
            mode="markers",
            marker=dict(color="black", size=7, symbol="circle"),
            showlegend=False,
            legendgroup=geno,
        ), row=1, col=col)

    # Add p-value annotation
    wt_vals = age_data.loc[age_data["Genotype"] == "WT", "Astrocytes_per_mm2"]
    ko_vals = age_data.loc[age_data["Genotype"] == "KO", "Astrocytes_per_mm2"]
    _, p = stats.ttest_ind(wt_vals, ko_vals, equal_var=False)
    sig = "***" if p < 0.001 else "**" if p < 0.01 else "*" if p < 0.05 else "ns"
    y_max = age_animals["Mean_per_mm2"].max() * 1.25

    fig.add_annotation(
        x=0.5, y=y_max,
        xref=f"x{col if col > 1 else ''} domain",
        yref=f"y{col if col > 1 else ''}",
        text=f"p = {p:.4f} ({sig})",
        showarrow=False,
        font=dict(size=12),
        row=1, col=col,
    )

fig.update_layout(
    title="Astrocyte Density: WT vs KO by Age",
    yaxis_title="Astrocytes per mm²",
    template="plotly_white",
    barmode="group",
    legend_title="Genotype",
)

fig.write_html("astrocyte_density.html")
print("\nPlot saved to astrocyte_density.html")
