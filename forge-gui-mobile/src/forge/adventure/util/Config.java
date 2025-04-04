package forge.adventure.util;

import com.badlogic.gdx.files.FileHandle;
import com.badlogic.gdx.graphics.g2d.TextureAtlas;
import com.badlogic.gdx.utils.Array;
import com.badlogic.gdx.utils.Json;
import com.badlogic.gdx.utils.JsonWriter;
import com.badlogic.gdx.utils.ObjectMap;
import forge.CardStorageReader;
import forge.Forge;
import forge.ImageKeys;
import forge.adventure.data.ConfigData;
import forge.adventure.data.DifficultyData;
import forge.adventure.data.RewardData;
import forge.adventure.data.SettingData;
import forge.card.*;
import forge.deck.Deck;
import forge.deck.DeckProxy;
import forge.deck.DeckgenUtil;
import forge.gui.GuiBase;
import forge.item.PaperCard;
import forge.localinstance.properties.ForgeConstants;
import forge.localinstance.properties.ForgePreferences;
import forge.localinstance.properties.ForgeProfileProperties;
import forge.model.FModel;
import forge.util.FileUtil;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;

/**
 * Main resource class to access files from the selected adventure
 */
public class Config {
    private static Config currentConfig;
    private final String prefix;
    private final HashMap<String, FileHandle> Cache = new HashMap<String, FileHandle>();
    private ConfigData configData;
    private final String[] adventures;
    private SettingData settingsData;
    private String Lang = "en-us";
    private final String plane;

    static public Config instance()
    {
        if(currentConfig==null)
            currentConfig=new Config();
        return currentConfig;
    }
    private Config() {

        String path= resPath();
         adventures = new File(GuiBase.isAndroid() ? ForgeConstants.ADVENTURE_DIR : path + "/res/adventure").list();
        try
        {
            settingsData = new Json().fromJson(SettingData.class, new FileHandle(ForgeConstants.USER_ADVENTURE_DIR +  "settings.json"));

        }
        catch (Exception e)
        {
            settingsData=new SettingData();
        }
        if(settingsData.plane==null||settingsData.plane.isEmpty())
        {
            if(adventures!=null&&adventures.length>=1)
                settingsData.plane=adventures[0];
        }
        plane=settingsData.plane;

        if(settingsData.width==0||settingsData.height==0)
        {
            settingsData.width=1280;
            settingsData.height=720;
        }
        if(settingsData.videomode == null || settingsData.videomode.isEmpty())
            settingsData.videomode="720p";
        //reward card display fine tune
        if(settingsData.rewardCardAdj == null || settingsData.rewardCardAdj == 0f)
            settingsData.rewardCardAdj=1f;
        //tooltip fine tune
        if(settingsData.cardTooltipAdj == null || settingsData.cardTooltipAdj == 0f)
            settingsData.cardTooltipAdj=1f;
        //reward card display fine tune landscape
        if(settingsData.rewardCardAdjLandscape == null || settingsData.rewardCardAdjLandscape == 0f)
            settingsData.rewardCardAdjLandscape=1f;
        //tooltip fine tune landscape
        if(settingsData.cardTooltipAdjLandscape == null || settingsData.cardTooltipAdjLandscape == 0f)
            settingsData.cardTooltipAdjLandscape=1f;

        prefix = getPlanePath(settingsData.plane);

        currentConfig = this;
        if (FModel.getPreferences() != null)
            Lang = FModel.getPreferences().getPref(ForgePreferences.FPref.UI_LANGUAGE);
        try
        {
            configData = new Json().fromJson(ConfigData.class, new FileHandle(prefix + "config.json"));

        }
        catch (Exception e)
        {
            e.printStackTrace();
            configData=new ConfigData();
        }


    }

    private String resPath() {

        return GuiBase.isAndroid() ? ForgeConstants.ASSETS_DIR : Files.exists(Paths.get("./res"))?"./":"../forge-gui/";
    }

    public String getPlanePath(String plane) {
        if(plane.startsWith("<user>"))
        {
            return ForgeConstants.USER_ADVENTURE_DIR + "/userplanes/" + plane.substring("<user>".length()) + "/";
        }
        else
        {
            return resPath() + "/res/adventure/" + plane + "/";
        }
    }

    public ConfigData getConfigData() {
        return configData;
    }

    public String getPrefix() {
        return prefix;
    }

    public String getFilePath(String path) {
        return prefix + path;
    }

    public FileHandle getFile(String path) {
        String fullPath = prefix + path;
        fullPath = fullPath.replace("//","/");
        if (!Cache.containsKey(fullPath)) {

            String fileName = fullPath.replaceFirst("[.][^.]+$", "");
            String ext = fullPath.substring(fullPath.lastIndexOf('.'));
            String langFile = fileName + "-" + Lang + ext;
            if (Files.exists(Paths.get(langFile))) {
                Cache.put(fullPath, new FileHandle(langFile));
            } else {
                Cache.put(fullPath, new FileHandle(fullPath));
            }
        }
        return Cache.get(fullPath);
    }


    public String getPlane() {
        return plane.replace("<user>","user_");
    }

    public String[] colorIdNames() {

        return configData.colorIdNames;
    }
    public String[] colorIds() {

        return configData.colorIds;
    }
    public Deck starterDeck(ColorSet color, DifficultyData difficultyData, AdventureModes mode,int index) {
        switch (mode)
        {
            case Constructed:
                for(ObjectMap.Entry<String, String> entry:difficultyData.constructedStarterDecks)
                {
                    if(ColorSet.fromNames(entry.key.toCharArray()).getColor()==color.getColor())
                    {
                        return CardUtil.getDeck(entry.value, false, false, "", false, false);
                    }
                }
            case Standard:

                for(ObjectMap.Entry<String, String> entry:difficultyData.starterDecks)
                {
                    if(ColorSet.fromNames(entry.key.toCharArray()).getColor()==color.getColor())
                    {
                        return CardUtil.getDeck(entry.value, false, false, "", false, false);
                    }
                }
            case Chaos:
                return DeckgenUtil.getRandomOrPreconOrThemeDeck("", false, false, false);
            case Custom:
                return DeckProxy.getAllCustomStarterDecks().get(index).getDeck();
            case Pile:
                for(ObjectMap.Entry<String, String> entry:difficultyData.pileDecks)
                {
                    if(ColorSet.fromNames(entry.key.toCharArray()).getColor()==color.getColor())
                    {
                        return CardUtil.getDeck(entry.value, false, false, "", false, false);
                    }
                }
        }
        return null;
    }

    public TextureAtlas getAtlas(String spriteAtlas) {
        String fileName = getFile(spriteAtlas).path();
        if (!Forge.getAssets().manager().contains(fileName, TextureAtlas.class)) {
            Forge.getAssets().manager().load(fileName, TextureAtlas.class);
            Forge.getAssets().manager().finishLoadingAsset(fileName);
        }
        return Forge.getAssets().manager().get(fileName);
    }
    public SettingData getSettingData()
    {
        return settingsData;
    }
    public Array<String> getAllAdventures()
    {
        String path=ForgeConstants.USER_ADVENTURE_DIR + "/userplanes/";
        Array<String> adventures = new Array<String>();
        if(new File(path).exists())
            adventures.addAll(new File(path).list());
        for(int i=0;i<adventures.size;i++)
        {
            adventures.set(i,"<user>"+adventures.get(i));
        }
        adventures.addAll(this.adventures);
        return adventures;
    }

    public void saveSettings() {

        Json json = new Json(JsonWriter.OutputType.json);
        FileHandle handle = new FileHandle(ForgeProfileProperties.getUserDir() +  "/adventure/settings.json");
        handle.writeString(json.prettyPrint(json.toJson(settingsData, SettingData.class)),false);

    }

    public void loadResources() {
        RewardData.getAllCards();//initialize before loading custom cards
        final CardRules.Reader rulesReader = new CardRules.Reader();
        ImageKeys.ADVENTURE_CARD_PICS_DIR=Config.currentConfig.getFilePath(forge.adventure.util.Paths.CUSTOM_CARDS_PICS);// not the cleanest solution
        for(File cardFile:new File(getFilePath(forge.adventure.util.Paths.CUSTOM_CARDS)).listFiles())
        {

            FileInputStream fileInputStream;
            try {
                fileInputStream = new FileInputStream(cardFile);
                rulesReader.reset();
                final List<String> lines =  FileUtil.readAllLines(new InputStreamReader(fileInputStream, Charset.forName(CardStorageReader.DEFAULT_CHARSET_NAME)), true);
                CardRules rules=  rulesReader.readCard(lines, com.google.common.io.Files.getNameWithoutExtension(cardFile.getName()));
                rules.setCustom();
                PaperCard card=new PaperCard(rules, CardEdition.UNKNOWN.getCode(), CardRarity.Special){
                    @Override
                    public String getImageKey(boolean altState) {
                        return ImageKeys.ADVENTURECARD_PREFIX + getName();
                    }
                };
                FModel.getMagicDb().getCommonCards().addCard(card);
            } catch (FileNotFoundException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
