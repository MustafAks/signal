package com.binance.signal;

import java.text.SimpleDateFormat;
import java.util.Date;

public class TradePrediction {
    private String symbol;
    private Date predictionDate; // Tahminin geçerli olacağı gün (örneğin yarın)
    private String tradeType; // "Long (Alım)" veya "Short (Satım)"
    private double predictedEntry;
    private double predictedExit;
    private double predictedProfit; // Yüzde cinsinden tahmini kâr/zarar

    // Ertesi gün gerçekleşen sonuçlar
    private double actualEntry;
    private double actualExit;
    private double actualProfit;

    public TradePrediction(String symbol, Date predictionDate, String tradeType, double predictedEntry, double predictedExit, double predictedProfit) {
        this.symbol = symbol;
        this.predictionDate = predictionDate;
        this.tradeType = tradeType;
        this.predictedEntry = predictedEntry;
        this.predictedExit = predictedExit;
        this.predictedProfit = predictedProfit;
    }

    public String getSymbol() { return symbol; }
    public Date getPredictionDate() { return predictionDate; }
    public String getTradeType() { return tradeType; }
    public double getPredictedEntry() { return predictedEntry; }
    public double getPredictedExit() { return predictedExit; }
    public double getPredictedProfit() { return predictedProfit; }

    public double getActualEntry() { return actualEntry; }
    public double getActualExit() { return actualExit; }
    public double getActualProfit() { return actualProfit; }

    public void setActualEntry(double actualEntry) { this.actualEntry = actualEntry; }
    public void setActualExit(double actualExit) { this.actualExit = actualExit; }
    public void setActualProfit(double actualProfit) { this.actualProfit = actualProfit; }

    // Rapor metotları
    public String getPredictionReport() {
        return String.format("Tahmin Tarihi: %s\nİşlem Türü: %s\nTahmini Giriş: %.2f\nTahmini Çıkış: %.2f\nTahmini Kar/Zarar: %.2f%%",
                new SimpleDateFormat("yyyyMMdd").format(predictionDate), tradeType, predictedEntry, predictedExit, predictedProfit);
    }

    public String getPerformanceReport() {
        return String.format("Gerçek Giriş: %.2f\nGerçek Çıkış: %.2f\nGerçek Kar/Zarar: %.2f%%", actualEntry, actualExit, actualProfit);
    }
}
