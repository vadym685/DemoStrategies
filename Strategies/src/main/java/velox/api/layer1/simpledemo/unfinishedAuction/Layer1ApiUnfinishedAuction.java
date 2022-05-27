package velox.api.layer1.simpledemo.unfinishedAuction;

import java.awt.Color;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.Insets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import velox.api.layer1.Layer1ApiAdminAdapter;
import velox.api.layer1.Layer1ApiFinishable;
import velox.api.layer1.Layer1ApiInstrumentListener;
import velox.api.layer1.Layer1ApiProvider;
import velox.api.layer1.Layer1CustomPanelsGetter;
import velox.api.layer1.annotations.Layer1ApiVersion;
import velox.api.layer1.annotations.Layer1ApiVersionValue;
import velox.api.layer1.annotations.Layer1Attachable;
import velox.api.layer1.annotations.Layer1StrategyName;
import velox.api.layer1.common.ListenableHelper;
import velox.api.layer1.common.Log;
import velox.api.layer1.data.InstrumentInfo;
import velox.api.layer1.data.TradeInfo;
import velox.api.layer1.datastructure.events.TradeAggregationEvent;
import velox.api.layer1.layers.strategies.interfaces.CalculatedResultListener;
import velox.api.layer1.layers.strategies.interfaces.InvalidateInterface;
import velox.api.layer1.layers.strategies.interfaces.Layer1IndicatorColorInterface;
import velox.api.layer1.layers.strategies.interfaces.OnlineCalculatable;
import velox.api.layer1.layers.strategies.interfaces.OnlineValueCalculatorAdapter;
import velox.api.layer1.messages.UserMessageLayersChainCreatedTargeted;
import velox.api.layer1.messages.indicators.DataStructureInterface;
import velox.api.layer1.messages.indicators.DataStructureInterface.StandardEvents;
import velox.api.layer1.messages.indicators.DataStructureInterface.TreeResponseInterval;
import velox.api.layer1.messages.indicators.IndicatorColorInterface;
import velox.api.layer1.messages.indicators.IndicatorColorScheme;
import velox.api.layer1.messages.indicators.IndicatorLineStyle;
import velox.api.layer1.messages.indicators.Layer1ApiDataInterfaceRequestMessage;
import velox.api.layer1.messages.indicators.Layer1ApiUserMessageModifyIndicator;
import velox.api.layer1.messages.indicators.SettingsAccess;
import velox.api.layer1.settings.Layer1ConfigSettingsInterface;
import velox.api.layer1.messages.indicators.Layer1ApiUserMessageModifyIndicator.GraphType;
import velox.colors.ColorsChangedListener;
import velox.gui.StrategyPanel;
import velox.gui.colors.ColorsConfigItem;

@Layer1Attachable
@Layer1StrategyName("Unfinished auction")
@Layer1ApiVersion(Layer1ApiVersionValue.VERSION2)
public class Layer1ApiUnfinishedAuction implements
        Layer1ApiFinishable,
        Layer1ApiAdminAdapter,
        Layer1ApiInstrumentListener, OnlineCalculatable,
        Layer1CustomPanelsGetter,
        Layer1ConfigSettingsInterface,
        Layer1IndicatorColorInterface {

    private static final String INDICATOR_NAME_TRADE = "Trade markers";
    private static final String INDICATOR_LINE_COLOR_NAME = "Trade markers line";
    private static final Color INDICATOR_LINE_DEFAULT_COLOR = Color.RED;
    private Layer1ApiProvider provider;

    private Map<String, UnfinishedAuctionSettings> settingsMap = new HashMap<>();

    private SettingsAccess settingsAccess;

    private Map<String, String> indicatorsFullNameToUserName = new HashMap<>();
    private Map<String, String> indicatorsUserNameToFullName = new HashMap<>();

    private Set<TreeResponseInterval> unfinishedAuctionsMap = new HashSet<>();
    private Map<String, InvalidateInterface> invalidateInterfaceMap = new ConcurrentHashMap<>();

    private Map<String, Double> pipsMap = new ConcurrentHashMap<>();

    private DataStructureInterface dataStructureInterface;

    private Object locker = new Object();

    public Layer1ApiUnfinishedAuction(Layer1ApiProvider provider) {
        this.provider = provider;

        ListenableHelper.addListeners(provider, this);
    }

    @Override
    public void finish() {
        synchronized (indicatorsFullNameToUserName) {
            for (String userName : indicatorsFullNameToUserName.values()) {
                provider.sendUserMessage(new Layer1ApiUserMessageModifyIndicator(Layer1ApiUnfinishedAuction.class, userName, false));
            }
        }
        invalidateInterfaceMap.clear();
    }

    private Layer1ApiUserMessageModifyIndicator getUserMessageAdd(String userName,
                                                                  IndicatorLineStyle lineStyle, boolean isAddWidget) {
        return new Layer1ApiUserMessageModifyIndicator(Layer1ApiUnfinishedAuction.class, userName, true,
                new IndicatorColorScheme() {
                    @Override
                    public ColorDescription[] getColors() {
                        return new ColorDescription[]{
                                new ColorDescription(Layer1ApiUnfinishedAuction.class, INDICATOR_LINE_COLOR_NAME, INDICATOR_LINE_DEFAULT_COLOR, false),
                        };
                    }

                    @Override
                    public String getColorFor(Double value) {
                        return INDICATOR_LINE_COLOR_NAME;
                    }

                    @Override
                    public ColorIntervalResponse getColorIntervalsList(double valueFrom, double valueTo) {
                        return new ColorIntervalResponse(new String[]{INDICATOR_LINE_COLOR_NAME}, new double[]{});
                    }
                }, Layer1ApiUnfinishedAuction.this, lineStyle, Color.white, Color.black, null,
                null, null, null, null, GraphType.PRIMARY, isAddWidget, false, null, this, null);
    }

    @Override
    public void onUserMessage(Object data) {
        if (data.getClass() == UserMessageLayersChainCreatedTargeted.class) {
            UserMessageLayersChainCreatedTargeted message = (UserMessageLayersChainCreatedTargeted) data;
            if (message.targetClass == getClass()) {
                provider.sendUserMessage(new Layer1ApiDataInterfaceRequestMessage(
                        dataStructureInterface -> {
                            this.dataStructureInterface = dataStructureInterface;
                            invalidateInterfaceMap.values().forEach(InvalidateInterface::invalidate);
                        }));
                addIndicator(INDICATOR_NAME_TRADE);
            }
        }
    }

    @Override
    public void onInstrumentAdded(String alias, InstrumentInfo instrumentInfo) {
        pipsMap.put(alias, instrumentInfo.pips);

    }

    @Override
    public void onInstrumentRemoved(String alias) {
    }

    @Override
    public void onInstrumentNotFound(String symbol, String exchange, String type) {
    }

    @Override
    public void onInstrumentAlreadySubscribed(String symbol, String exchange, String type) {
    }

    @Override
    public void calculateValuesInRange(String indicatorName, String alias, long t0, long intervalWidth, int intervalsNumber,
                                       CalculatedResultListener listener) {
        if (dataStructureInterface == null) {
            listener.setCompleted();
            return;
        }

        long t1 = t0 + (intervalWidth * intervalsNumber);
        long newIntervalWidth = Long.parseLong("30000000000");
        int newIntervalNumber = (int) ((t1 - t0) / newIntervalWidth);

        ArrayList<TreeResponseInterval> newIntervalResponse = dataStructureInterface.get(t0, newIntervalWidth, newIntervalNumber, alias,
                new StandardEvents[]{StandardEvents.TRADE});

        ArrayList<TreeResponseInterval> intervalResponse = dataStructureInterface.get(t0, intervalWidth, intervalsNumber, alias,
                new StandardEvents[]{StandardEvents.TRADE});

        double lastPrice = ((TradeAggregationEvent) intervalResponse.get(0).events.get(StandardEvents.TRADE.toString())).lastPrice;

        for (TreeResponseInterval responseInterval : newIntervalResponse) {
            Map<Double, Map<Integer, Integer>> askAggressorMap = ((TradeAggregationEvent) responseInterval.events.get(StandardEvents.TRADE.toString())).askAggressorMap;
            Map<Double, Map<Integer, Integer>> bidAggressorMap = ((TradeAggregationEvent) responseInterval.events.get(StandardEvents.TRADE.toString())).bidAggressorMap;

            if (askAggressorMap.isEmpty() || bidAggressorMap.isEmpty()) {
                continue;
            }
            Double minAskPrice = Collections.min(askAggressorMap.keySet());
            Double minBidPrice = Collections.min(bidAggressorMap.keySet());

            Double maxAskPrice = Collections.max(askAggressorMap.keySet());
            Double maxBidPrice = Collections.max(bidAggressorMap.keySet());

            if (minAskPrice.equals(minBidPrice) || maxBidPrice.equals(maxAskPrice)) {
                unfinishedAuctionsMap.add(responseInterval);
            }

        }

        for (int i = 1; i <= intervalsNumber; ++i) {
            TradeAggregationEvent trades = (TradeAggregationEvent) intervalResponse.get(i).events.get(StandardEvents.TRADE.toString());

            if (!Double.isNaN(trades.lastPrice)) {
                lastPrice = trades.lastPrice;
            }

            if (trades.askAggressorMap.isEmpty() && trades.bidAggressorMap.isEmpty()) {
                listener.provideResponse(lastPrice);
            }
        }

        listener.setCompleted();
    }

    @Override
    public OnlineValueCalculatorAdapter createOnlineValueCalculator(String indicatorName, String indicatorAlias, long time,
                                                                    Consumer<Object> listener, InvalidateInterface invalidateInterface) {
        String userName = indicatorsFullNameToUserName.get(indicatorName);
        invalidateInterfaceMap.put(userName, invalidateInterface);

        if (dataStructureInterface == null) {
            return new OnlineValueCalculatorAdapter() {
            };
        }

        switch (userName) {
            case INDICATOR_NAME_TRADE:
                return new OnlineValueCalculatorAdapter() {
                    @Override
                    public void onTrade(String alias, double price, int size, TradeInfo tradeInfo) {

                    }
                };
            default:
                throw new IllegalArgumentException("Unknown indicator name " + indicatorName);
        }
    }

    @Override
    public StrategyPanel[] getCustomGuiFor(String alias, String indicatorName) {
        StrategyPanel panel = new StrategyPanel("Colors", new GridBagLayout());

        panel.setLayout(new GridBagLayout());
        GridBagConstraints gbConst;

        IndicatorColorInterface indicatorColorInterface = new IndicatorColorInterface() {
            @Override
            public void set(String name, Color color) {
                setColor(alias, name, color);
            }

            @Override
            public Color getOrDefault(String name, Color defaultValue) {
                Color color = getSettingsFor(alias).getColor(name);
                return color == null ? defaultValue : color;
            }

            @Override
            public void addColorChangeListener(ColorsChangedListener listener) {
            }
        };

        ColorsConfigItem configItemLines = new ColorsConfigItem(INDICATOR_LINE_COLOR_NAME, INDICATOR_LINE_COLOR_NAME, true,
                INDICATOR_LINE_DEFAULT_COLOR, indicatorColorInterface, new ColorsChangedListener() {
            @Override
            public void onColorsChanged() {
                InvalidateInterface invalidaInterface = invalidateInterfaceMap.get(INDICATOR_NAME_TRADE);
                if (invalidaInterface != null) {
                    invalidaInterface.invalidate();
                }
            }
        });

        gbConst = new GridBagConstraints();
        gbConst.gridx = 0;
        gbConst.gridy = 0;
        gbConst.weightx = 1;
        gbConst.insets = new Insets(5, 5, 5, 5);
        gbConst.fill = GridBagConstraints.HORIZONTAL;
        panel.add(configItemLines, gbConst);

        return new StrategyPanel[]{panel};
    }


    public void addIndicator(String userName) {
        Layer1ApiUserMessageModifyIndicator message = null;
        switch (userName) {
            case INDICATOR_NAME_TRADE:
                message = getUserMessageAdd(userName, IndicatorLineStyle.SHORT_DASHES_WIDE_LEFT_NARROW_RIGHT, true);
                break;
            default:
                Log.warn("Unknwon name for marker indicator: " + userName);
                break;
        }

        if (message != null) {
            synchronized (indicatorsFullNameToUserName) {
                indicatorsFullNameToUserName.put(message.fullName, message.userName);
                indicatorsUserNameToFullName.put(message.userName, message.fullName);
            }
            provider.sendUserMessage(message);
        }
    }

    @Override
    public void acceptSettingsInterface(SettingsAccess settingsAccess) {
        this.settingsAccess = settingsAccess;
    }

    private UnfinishedAuctionSettings getSettingsFor(String alias) {
        synchronized (locker) {
            UnfinishedAuctionSettings settings = settingsMap.get(alias);
            if (settings == null) {
                settings = new UnfinishedAuctionSettings();
            }
            return settings;
        }
    }


    @Override
    public void setColor(String alias, String name, Color color) {
        UnfinishedAuctionSettings settings = getSettingsFor(alias);
        settings.setColor(name, color);
    }

    @Override
    public Color getColor(String alias, String name) {
        Color color = getSettingsFor(alias).getColor(name);
        if (color == null) {

            color = INDICATOR_LINE_DEFAULT_COLOR;

        }

        return color;
    }

    @Override
    public void addColorChangeListener(ColorsChangedListener listener) {
        // every one of our colors is modified only from one place
    }
}
