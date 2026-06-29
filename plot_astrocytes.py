import pandas as pd
import re
import plotly.graph_objects as go

FILES = {
    "2026-05-25": "astrocyte_counts_2026_05_25.csv",
    "2026-05-29": "astrocyte_counts_2026_05_29.csv",
}

def load(date, path):
    df = pd.read_csv(path, usecols=["Image", "Astrocytes_per_mm2"])
    df = df.dropna(subset=["Astrocytes_per_mm2"])
    df = df.drop_duplicates()
    df["Animal"] = df["Image"].str.extract(r"RecognizedCode_(\d+)", expand=False).astype(int)
    df["Date"] = date
    return df

frames = [load(date, path) for date, path in FILES.items()]
data = pd.concat(frames, ignore_index=True)

summary = (
    data.groupby(["Date", "Animal"])["Astrocytes_per_mm2"]
    .agg(Mean="mean", SD="std", N="count")
    .round(2)
    .reset_index()
)

print(summary.to_string(index=False))

# --- Plot ---
colors = {"2026-05-25": "#1f77b4", "2026-05-29": "#ff7f0e"}
fig = go.Figure()

for date, grp in summary.groupby("Date"):
    color = colors[date]
    fig.add_trace(go.Bar(
        x=grp["Animal"].astype(str),
        y=grp["Mean"],
        error_y=dict(type="data", array=grp["SD"].tolist(), visible=True),
        name=date,
        marker_color=color,
    ))

    # overlay individual points
    pts = data[data["Date"] == date]
    animal_means = grp.set_index("Animal")["Mean"]
    fig.add_trace(go.Scatter(
        x=pts["Animal"].astype(str),
        y=pts["Astrocytes_per_mm2"],
        mode="markers",
        marker=dict(color=color, size=5, opacity=0.5, symbol="circle-open"),
        name=f"{date} (points)",
        showlegend=False,
    ))

fig.update_layout(
    title="Astrocyte Density by Animal",
    xaxis_title="Animal ID",
    yaxis_title="Astrocytes per mm²",
    barmode="group",
    template="plotly_white",
    legend_title="Date",
)

fig.write_html("astrocyte_density.html")
print("\nPlot saved to astrocyte_density.html")
