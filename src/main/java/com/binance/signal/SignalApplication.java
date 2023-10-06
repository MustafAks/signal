package com.binance.signal;

import org.json.JSONArray;
import org.json.JSONObject;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

@SpringBootApplication
public class SignalApplication {

    private static final int PERIOD = 14; // RSI hesaplama periyodu
    private static final Map<String, String> SYMBOLS_AND_API_URLS = new HashMap<>();

    private static String previousTrendDirection = "";

    private static final String SYMBOLS_AND_API_URLS_FILE = "SymbolsAndApiUrl";

    static {
        loadSymbolsAndApiUrlsFromFile();
    }

    private static void loadSymbolsAndApiUrlsFromFile() {
        try {
            Path path = Paths.get("src/main/resources/" + SignalApplication.SYMBOLS_AND_API_URLS_FILE);

            try (Stream<String> lines = Files.lines(path)) {
                lines.forEach(line -> {
                    String[] parts = line.split(",");
                    if (parts.length == 2) {
                        SYMBOLS_AND_API_URLS.put(parts[0].trim(), parts[1].trim());
                    }
                });
            }
        } catch (Exception e) {
            e.printStackTrace();
            System.out.println("SymbolsAndApiUrl dosyası okunamadı!");
        }
    }



    public static void main(String[] args) {
        while (true) {
            try {
                for (Map.Entry<String, String> entry : SYMBOLS_AND_API_URLS.entrySet()) {
                    String symbol = entry.getKey();
                    String apiUrl = entry.getValue();

                    // Her bir sembol için API verilerini al
                    URL url = new URL(apiUrl);
                    HttpURLConnection connection = (HttpURLConnection) url.openConnection();
                    connection.setRequestMethod("GET");

                    BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
                    StringBuilder response = new StringBuilder();
                    String line;

                    while ((line = reader.readLine()) != null) {
                        response.append(line);
                    }
                    reader.close();

                    // JSON verisini işle
                    JSONArray klines = new JSONArray(response.toString());
                    double[] closingPrices = new double[klines.length()];
                    double[] highPrices = new double[klines.length()];
                    double[] lowPrices = new double[klines.length()];

                    for (int i = 0; i < klines.length(); i++) {
                        JSONArray kline = klines.getJSONArray(i);
                        double closingPrice = Double.parseDouble(kline.getString(4));
                        double highPrice = Double.parseDouble(kline.getString(2));
                        double lowPrice = Double.parseDouble(kline.getString(3));

                        closingPrices[i] = closingPrice;
                        highPrices[i] = highPrice;
                        lowPrices[i] = lowPrice;
                    }

                    // Tarih bilgisini al
                    SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
                    String dateStr = dateFormat.format(new Date());

                    // Trend analizi yap ve sonucu yazdır
                    analyzeTrend(symbol, dateStr, closingPrices, highPrices, lowPrices);

                    // Bağlantıyı kapat
                    connection.disconnect();
                }

                Thread.sleep(240000); // 4 dakika bekle


            } catch (Exception e) {
                e.printStackTrace();
            }
        }
    }

    private static double calculateRSI(double[] closingPrices) {
        double gainSum = 0;
        double lossSum = 0;

        for (int i = 1; i < PERIOD; i++) {
            double priceChange = closingPrices[i] - closingPrices[i - 1];
            if (priceChange > 0) {
                gainSum += priceChange;
            } else {
                lossSum += Math.abs(priceChange);
            }
        }

        double avgGain = gainSum / PERIOD;
        double avgLoss = lossSum / PERIOD;

        double relativeStrength = avgGain / avgLoss;
        double rsi = 100 - (100 / (1 + relativeStrength));

        return rsi;
    }

    private static double calculateSMA(double[] closingPrices) {
        double sum = 0;

        for (int i = 0; i < PERIOD; i++) {
            sum += closingPrices[i];
        }

        double sma = sum / PERIOD;
        return sma;
    }

    private static double calculateEMA(double[] closingPrices) {
        double ema = 0;
        double multiplier = 2.0 / (PERIOD + 1);

        for (int i = 0; i < closingPrices.length; i++) {
            if (i == 0) {
                ema = closingPrices[i];
            } else {
                ema = (closingPrices[i] - ema) * multiplier + ema;
            }
        }

        return ema;
    }

    private static double[] calculateMACD(double[] closingPrices) {
        double[] macd = new double[2]; // 0: MACD, 1: MACD Sinyali
        int shortTermPeriod = 12; // Kısa vadeli EMA periyodu
        int longTermPeriod = 26; // Uzun vadeli EMA periyodu
        int signalPeriod = 9; // MACD sinyal periyodu

        if (closingPrices.length < longTermPeriod + signalPeriod) {
            // Yeterli veri yoksa, MACD ve sinyal hesaplamasını yapmayı beklemeyi tercih edebilirsiniz.
            return macd;
        }

        // EMA hesaplamaları
        double shortTermEma = calculateEMA(Arrays.copyOfRange(closingPrices, closingPrices.length - shortTermPeriod, closingPrices.length));
        double longTermEma = calculateEMA(Arrays.copyOfRange(closingPrices, closingPrices.length - longTermPeriod, closingPrices.length));

        macd[0] = shortTermEma - longTermEma;

        // MACD sinyali hesapla
        double[] macdForSignal = Arrays.copyOfRange(macd, 0, signalPeriod);
        double signalEma = calculateEMA(macdForSignal);
        macd[1] = signalEma;

        return macd;
    }

    private static double[] calculateStochasticOscillator(double[] highPrices, double[] lowPrices, double[] closingPrices) {
        int period = 14; // Stokastik Osilatör hesaplama periyodu

        if (highPrices.length < period) {
            // Yeterli veri yoksa, Stokastik Osilatör hesaplamasını yapmayı beklemeyi tercih edebilirsiniz.
            return new double[]{};
        }

        double[] stochasticOscillator = new double[closingPrices.length - period + 1];

        for (int i = period - 1; i < closingPrices.length; i++) {
            double[] highPricesSubset = Arrays.copyOfRange(highPrices, i - period + 1, i + 1);
            double[] lowPricesSubset = Arrays.copyOfRange(lowPrices, i - period + 1, i + 1);

            double highestHigh = Arrays.stream(highPricesSubset).max().orElse(0);
            double lowestLow = Arrays.stream(lowPricesSubset).min().orElse(0);

            double currentClose = closingPrices[i];

            double stochasticOscillatorValue = ((currentClose - lowestLow) / (highestHigh - lowestLow)) * 100;
            stochasticOscillator[i - (period - 1)] = stochasticOscillatorValue;
        }

        return stochasticOscillator;
    }

    private static double[] calculateBollingerBands(double[] closingPrices) {
        int bollingerPeriod = 20; // Bollinger Bantları periyodu
        double bollingerMultiplier = 2.0; // Bollinger Bantları çarpanı

        if (closingPrices.length < bollingerPeriod) {
            // Yeterli veri yoksa, Bollinger Bantları hesaplamasını yapmayı beklemeyi tercih edebilirsiniz.
            return new double[]{0, 0, 0}; // Orta Bollinger Bandı, Üst Bollinger Bandı, Alt Bollinger Bandı
        }

        double[] middleBand = new double[closingPrices.length - bollingerPeriod + 1];
        double[] upperBand = new double[closingPrices.length - bollingerPeriod + 1];
        double[] lowerBand = new double[closingPrices.length - bollingerPeriod + 1];

        for (int i = bollingerPeriod - 1, j = 0; i < closingPrices.length; i++, j++) {
            double[] subset = Arrays.copyOfRange(closingPrices, i - bollingerPeriod + 1, i + 1);
            double sma = calculateSMA(subset);
            double stdDev = calculateStandardDeviation(subset);

            middleBand[j] = sma;
            upperBand[j] = sma + (bollingerMultiplier * stdDev);
            lowerBand[j] = sma - (bollingerMultiplier * stdDev);
        }

        return new double[]{middleBand[middleBand.length - 1], upperBand[upperBand.length - 1], lowerBand[lowerBand.length - 1]};
    }

    private static double calculateStandardDeviation(double[] values) {
        double mean = calculateSMA(values);
        double sumSquaredDifferences = 0;

        for (double value : values) {
            sumSquaredDifferences += Math.pow(value - mean, 2);
        }

        double variance = sumSquaredDifferences / values.length;
        return Math.sqrt(variance);
    }


    private static double[] calculateFibonacciRetracementLevels(double[] highPrices, double[] lowPrices) {
        double[] fibonacciLevels = new double[5];

        if (highPrices.length < 50 || lowPrices.length < 50) {
            return fibonacciLevels;
        }

        // Son 50 mumun en yüksek ve en düşük fiyatlarını al
        double[] last50Highs = Arrays.copyOfRange(highPrices, highPrices.length - 50, highPrices.length);
        double[] last50Lows = Arrays.copyOfRange(lowPrices, lowPrices.length - 50, lowPrices.length);

        double recentHigh = Arrays.stream(last50Highs).max().getAsDouble();
        double recentLow = Arrays.stream(last50Lows).min().getAsDouble();

        // %23.6 seviyesi
        fibonacciLevels[0] = recentHigh - ((recentHigh - recentLow) * 0.236);

        // %38.2 seviyesi
        fibonacciLevels[1] = recentHigh - ((recentHigh - recentLow) * 0.382);

        // %50.0 seviyesi
        fibonacciLevels[2] = recentHigh - ((recentHigh - recentLow) * 0.5);

        // %61.8 seviyesi
        fibonacciLevels[3] = recentHigh - ((recentHigh - recentLow) * 0.618);

        // %100.0 seviyesi
        fibonacciLevels[4] = recentLow;

        return fibonacciLevels;
    }


    private static double calculateADX(double[] highPrices, double[] lowPrices, double[] closingPrices, int period) {
        if (highPrices.length < period) {
            // Yeterli veri yoksa, hesaplama yapmayı beklemeyi tercih edebilirsiniz.
            return -1;
        }

        double[] positiveDM = new double[highPrices.length];
        double[] negativeDM = new double[highPrices.length];
        double[] trueRange = new double[highPrices.length];
        double[] averageTrueRange = new double[highPrices.length];
        double[] positiveDI = new double[highPrices.length];
        double[] negativeDI = new double[highPrices.length];
        double[] DX = new double[highPrices.length];
        double[] ADX = new double[highPrices.length];

        for (int i = 1; i < highPrices.length; i++) {
            double highDiff = highPrices[i] - highPrices[i - 1];
            double lowDiff = lowPrices[i - 1] - lowPrices[i];
            double highTR = Math.max(highDiff, 0);
            double lowTR = Math.max(lowDiff, 0);

            trueRange[i] = Math.max(highPrices[i] - lowPrices[i], Math.max(highTR, lowTR));

            if (i >= period) {
                double sumTR = 0;
                double sumPositiveDM = 0;
                double sumNegativeDM = 0;

                for (int j = i - period + 1; j <= i; j++) {
                    sumTR += trueRange[j];
                    double highDiff2 = highPrices[j] - highPrices[j - 1];
                    double lowDiff2 = lowPrices[j - 1] - lowPrices[j];
                    double highTR2 = Math.max(highDiff2, 0);
                    double lowTR2 = Math.max(lowDiff2, 0);

                    double positiveDMValue = (highTR2 > lowTR2) ? highTR2 : 0;
                    double negativeDMValue = (lowTR2 > highTR2) ? lowTR2 : 0;

                    sumPositiveDM += positiveDMValue;
                    sumNegativeDM += negativeDMValue;
                }

                averageTrueRange[i] = sumTR / period;
                positiveDM[i] = sumPositiveDM / period;
                negativeDM[i] = sumNegativeDM / period;

                double positiveDIValue = (positiveDM[i] / averageTrueRange[i]) * 100;
                double negativeDIValue = (negativeDM[i] / averageTrueRange[i]) * 100;

                positiveDI[i] = positiveDIValue;
                negativeDI[i] = negativeDIValue;

                if ((positiveDIValue + negativeDIValue) != 0) {
                    DX[i] = Math.abs((positiveDIValue - negativeDIValue) / (positiveDIValue + negativeDIValue)) * 100;
                }

                if (i >= period + period - 1) {
                    double sumDX = 0;
                    for (int k = i - period + 1; k <= i; k++) {
                        sumDX += DX[k];
                    }
                    ADX[i] = sumDX / period;
                }
            }
        }

        return ADX[ADX.length - 1];
    }

    private static double[] calculateParabolicSAR(double[] highPrices, double[] lowPrices) {
        double[] sarValues = new double[highPrices.length];
        double accelerationFactor = 0.02; // Başlangıç hızlandırma faktörü
        double maxAccelerationFactor = 0.20; // Maksimum hızlandırma faktörü
        double accelerationIncrement = 0.02; // Hızlandırma faktörü artışı

        double sar = lowPrices[0]; // İlk SAR değeri, ilk verinin en düşük fiyatıyla başlar
        double extremePoint = highPrices[0]; // İlk ekstrem nokta, ilk verinin en yüksek fiyatıyla başlar
        boolean trendUp = true; // SAR trendi yukarıda başlar

        sarValues[0] = sar; // İlk SAR değeri

        for (int i = 1; i < highPrices.length; i++) {
            if (trendUp) {
                if (highPrices[i] > extremePoint) {
                    extremePoint = highPrices[i];
                    accelerationFactor += accelerationIncrement;
                    if (accelerationFactor > maxAccelerationFactor) {
                        accelerationFactor = maxAccelerationFactor;
                    }
                }

                sar += accelerationFactor * (extremePoint - sar);

                if (sar > lowPrices[i]) {
                    trendUp = false;
                    extremePoint = lowPrices[i];
                    accelerationFactor = 0.02; // Başlangıç hızlandırma faktörüne geri dön
                }
            } else {
                if (lowPrices[i] < extremePoint) {
                    extremePoint = lowPrices[i];
                    accelerationFactor += accelerationIncrement;
                    if (accelerationFactor > maxAccelerationFactor) {
                        accelerationFactor = maxAccelerationFactor;
                    }
                }

                sar += accelerationFactor * (extremePoint - sar);

                if (sar < highPrices[i]) {
                    trendUp = true;
                    extremePoint = highPrices[i];
                    accelerationFactor = 0.02; // Başlangıç hızlandırma faktörüne geri dön
                }
            }

            sarValues[i] = sar;
        }

        return sarValues;
    }


    private static void analyzeTrend(String symbol, String dateStr, double[] closingPrices, double[] highPrices, double[] lowPrices) {
        // RSI hesapla
        double rsi = calculateRSI(closingPrices);

        // SMA ve EMA hesapla
        double sma = calculateSMA(closingPrices);
        double ema = calculateEMA(closingPrices);

        // MACD hesapla
        double[] macd = calculateMACD(closingPrices);

        // Stokastik Osilatör hesapla
        double[] stochasticOscillator = calculateStochasticOscillator(highPrices, lowPrices, closingPrices);

        // Bollinger Bantlarını hesapla
        double[] bollingerBands = calculateBollingerBands(closingPrices);

        // Fibonacci Retracement seviyelerini hesapla
        double[] fibonacciLevels = calculateFibonacciRetracementLevels(highPrices, lowPrices);

        double adx = calculateADX(highPrices, lowPrices, closingPrices, 14);

        double[] parabolicSAR = calculateParabolicSAR(highPrices, lowPrices);


        // Trend yönünü belirle
        String trendDirection = "";
        String newTrendDirection = "";

        // Fibonacci seviyelerini kullanarak destek ve direnç seviyelerini belirle
        double supportLevel = fibonacciLevels[1]; // Örnek olarak %38.2 seviyesini destek seviyesi olarak kullanıyoruz
        double resistanceLevel = fibonacciLevels[2]; // Örnek olarak %50.0 seviyesini direnç seviyesi olarak kullanıyoruz

        double currentPrice = getCoinPrice(symbol);

        // Trend yönü ve Fibonacci seviyelerini sonuçlara ekle
        newTrendDirection += " Destek Seviyesi: " + supportLevel + ", Direnç Seviyesi: " + resistanceLevel;

        if (rsi > 70 && sma < ema && macd[0] > macd[1] && stochasticOscillator[stochasticOscillator.length - 1] > 80 && closingPrices[closingPrices.length - 1] > bollingerBands[0] && adx > 25) {
            // Parabolic SAR'ı kullanarak karşılaştırma yapın
            if (parabolicSAR[parabolicSAR.length - 1] < closingPrices[closingPrices.length - 1]) {
                newTrendDirection = "Short Position";
            } else {
                newTrendDirection = "Trend Belirsiz";
            }
        } else if (rsi < 30 && sma > ema && macd[0] < macd[1] && stochasticOscillator[stochasticOscillator.length - 1] < 20 && closingPrices[closingPrices.length - 1] < bollingerBands[1] && adx > 25) {
            // Parabolic SAR'ı kullanarak karşılaştırma yapın
            if (parabolicSAR[parabolicSAR.length - 1] > closingPrices[closingPrices.length - 1]) {
                newTrendDirection = "Long Position";
            } else {
                newTrendDirection = "Trend Belirsiz";
            }
        } else {
            newTrendDirection = "Trend Belirsiz";
        }

        // Yeni pozisyon açma sinyali gelirse ve önceki trend yönü farklıysa Telegram'a mesaj gönder
        if (!newTrendDirection.equals(previousTrendDirection)) {
            if (newTrendDirection.equals("Long Position") || newTrendDirection.equals("Short Position")) {
                // Pozisyon açma sinyali üretildiğinde, pozisyon kapatma sinyalini kontrol ediyoruz
                if (shouldCloseTrade(newTrendDirection, closingPrices, rsi, macd, stochasticOscillator, bollingerBands)) {
                    // Eğer kapatma koşulları sağlanırsa Telegram'a kapatma sinyali gönderiyoruz
                    sendTelegramMessage("Close Position", symbol, currentPrice, supportLevel, resistanceLevel);
                } else {
                    sendTelegramMessage(newTrendDirection, symbol, currentPrice, supportLevel, resistanceLevel);
                }
            } else if (newTrendDirection.equals("Trend Belirsiz")) {
                // Trend belirsiz olduğunda da mesaj göndermek isterseniz bu kısmı kullanabilirsiniz
                sendTelegramMessage(newTrendDirection, symbol, currentPrice, supportLevel, resistanceLevel);
            }
            previousTrendDirection = newTrendDirection;
        }


        // Update the trend direction
        trendDirection = newTrendDirection;
        // Check if the trend direction has changed
        if (!newTrendDirection.equals(previousTrendDirection)) {
            // Send a message to Telegram
            sendTelegramMessage(newTrendDirection, symbol, currentPrice, supportLevel, resistanceLevel);
            // Update the previous trend direction
            previousTrendDirection = newTrendDirection;
        }

        // Sonuçları yazdır
        System.out.println("-------------------------");
        System.out.println("Tarih: " + dateStr);
        System.out.println("Symbol: " + symbol);
        System.out.println("RSI: " + rsi);
        System.out.println("SMA: " + sma);
        System.out.println("EMA: " + ema);
        System.out.println("MACD: " + macd[0]);
        System.out.println("MACD Sinyali: " + macd[1]);
        System.out.println("Stokastik Osilatör: " + stochasticOscillator[stochasticOscillator.length - 1]);
        System.out.println("Bollinger Bantları: Üst - " + bollingerBands[0] + ", Alt - " + bollingerBands[1] + ", Orta - " + bollingerBands[2]);
        System.out.println("Fibonacci Retracement Seviyeleri: %23.6 - " + fibonacciLevels[0] + ", %38.2 - " + fibonacciLevels[1] + ", %50.0 - " + fibonacciLevels[2] + ", %61.8 - " + fibonacciLevels[3] + ", %100.0 - " + fibonacciLevels[4]);
        System.out.println("ADX: " + adx);
        System.out.println("Parabolik SAR: " + parabolicSAR[parabolicSAR.length - 1]);
        System.out.println("Trend Yönü: " + trendDirection);
        System.out.println("-------------------------");
    }

    private static boolean shouldCloseTrade(String currentTrendDirection, double[] closingPrices, double rsi, double[] macd, double[] stochasticOscillator, double[] bollingerBands) {
        double latestClosePrice = closingPrices[closingPrices.length - 1];

        if (currentTrendDirection.equals("Long Position")) {
            return macd[0] < macd[1] && rsi < 70 && stochasticOscillator[stochasticOscillator.length - 1] < 80 && latestClosePrice < bollingerBands[1];
        } else if (currentTrendDirection.equals("Short Position")) {
            return macd[0] > macd[1] && rsi > 30 && stochasticOscillator[stochasticOscillator.length - 1] > 20 && latestClosePrice > bollingerBands[2];
        }

        return false;
    }


    // Function to send a message to a Telegram bot
    private static void sendTelegramMessage(String action, String symbol, double currentPrice, double supportLevel, double resistanceLevel) {
        String message = "";

        switch (action) {
            case "Long Position":
                message = "🟢 <b>Alım Sinyali</b>: " + symbol + " - Güncel Fiyat: " + currentPrice + " - Destek Seviyesi: " + supportLevel + " - Direnç Seviyesi: " + resistanceLevel;
                break;
            case "Short Position":
                message = "🔴 <b>Satım Sinyali</b>: " + symbol + " - Güncel Fiyat: " + currentPrice + " - Destek Seviyesi: " + supportLevel + " - Direnç Seviyesi: " + resistanceLevel;
                break;
            case "Close Position":
                message = "🔵 <b>Kapama Sinyali</b>: " + symbol + " - Güncel Fiyat: " + currentPrice + " - Destek Seviyesi: " + supportLevel + " - Direnç Seviyesi: " + resistanceLevel;
                break;
            default:
                message = action + ": " + symbol + " - Güncel Fiyat: " + currentPrice + " - Destek Seviyesi: " + supportLevel + " - Direnç Seviyesi: " + resistanceLevel;
        }

        try {
            String botToken = "6482508265:AAEDUmyCM-ygU7BVO-txyykS7cKn5URspmY";  // Replace with your actual bot token
            long chatId = 1692398446;           // Replace with your actual chat ID

            String urlString = "https://api.telegram.org/bot" + botToken + "/sendMessage?chat_id=" + chatId + "&text=" + message + "&parse_mode=HTML";
            URL url = new URL(urlString);
            HttpURLConnection con = (HttpURLConnection) url.openConnection();
            con.setRequestMethod("GET");

            int responseCode = con.getResponseCode();
            if (responseCode == HttpURLConnection.HTTP_OK) {
                // Message sent successfully
                System.out.println("Telegram message sent: " + message);
            } else {
                // Handle error
                System.err.println("Error sending Telegram message. Response code: " + responseCode);
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static double getCoinPrice(String symbol) {
        try {
            String apiUrl = "https://api.binance.com/api/v3/ticker/price?symbol=" + symbol;
            URL url = new URL(apiUrl);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");

            BufferedReader reader = new BufferedReader(new InputStreamReader(connection.getInputStream()));
            StringBuilder response = new StringBuilder();
            String line;

            while ((line = reader.readLine()) != null) {
                response.append(line);
            }
            reader.close();

            JSONObject jsonResponse = new JSONObject(response.toString());
            return Double.parseDouble(jsonResponse.getString("price"));
        } catch (Exception e) {
            e.printStackTrace();
            return -1; // Hata durumunda -1 dönebilirsiniz.
        }
    }

}
