package com.filippo.VoiceTrackingApp.db;

public class Session {

    private int id;
    private int userId;
    private long timestamp;
    private long durata;
    private Double f0Mean;
    private Double f0Std;
    private Double pauseRatio;
    private Double energyMean;
    private Double energyStd;
    private Double jitter;
    private Double shimmer;
    private Double hnr;
    private Double cpp;

    public int getId() {
        return id;
    }

    public void setId(int id) {
        this.id = id;
    }

    public int getUserId() {
        return userId;
    }

    public void setUserId(int userId) {
        this.userId = userId;
    }

    public long getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(long timestamp) {
        this.timestamp = timestamp;
    }

    public long getDurata() {
        return durata;
    }

    public void setDurata(long durata) {
        this.durata = durata;
    }

    public Double getF0Mean() {
        return f0Mean;
    }

    public void setF0Mean(Double f0Mean) {
        this.f0Mean = f0Mean;
    }

    public Double getF0Std() {
        return f0Std;
    }

    public void setF0Std(Double f0Std) {
        this.f0Std = f0Std;
    }

    public Double getPauseRatio() {
        return pauseRatio;
    }

    public void setPauseRatio(Double pauseRatio) {
        this.pauseRatio = pauseRatio;
    }

    public Double getEnergyMean() {
        return energyMean;
    }

    public void setEnergyMean(Double energyMean) {
        this.energyMean = energyMean;
    }

    public Double getEnergyStd() {
        return energyStd;
    }

    public void setEnergyStd(Double energyStd) {
        this.energyStd = energyStd;
    }

    public Double getJitter() {
        return jitter;
    }

    public void setJitter(Double jitter) {
        this.jitter = jitter;
    }

    public Double getShimmer() {
        return shimmer;
    }

    public void setShimmer(Double shimmer) {
        this.shimmer = shimmer;
    }

    public Double getHnr() {
        return hnr;
    }

    public void setHnr(Double hnr) {
        this.hnr = hnr;
    }

    public Double getCpp() {
        return cpp;
    }

    public void setCpp(Double cpp) {
        this.cpp = cpp;
    }
}
