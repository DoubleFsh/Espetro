package com.example.hcrpoints.client.gui;

import com.example.hcrpoints.util.ModLogger;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphics;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;

import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.FormattedCharSequence;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * MD文件阅读界面
 * 用于显示和阅读HCRmdread文件夹中的MD文件
 */
public class MDRenderScreen extends Screen {
    // MD文件文件夹名称
    private static final String MD_FOLDER_NAME = "HCRmdread";
    
    // 组件尺寸常量
    private static final int FILE_LIST_BUTTON_HEIGHT = 20;
    private static final int LINE_HEIGHT = 15;
    private static final int TOC_BUTTON_HEIGHT = 15;
    private static final int TOP_BAR_HEIGHT = 30;
    private static final float LEFT_SIDE_RATIO = 1.0f / 3.0f;
    
    // 屏幕状态
    private enum ScreenState {
        FILE_LIST, // 文件列表状态
        FILE_CONTENT // 文件内容状态
    }
    
    // 屏幕状态和数据
    private ScreenState currentState = ScreenState.FILE_LIST;
    private File currentFile;
    
    // 按钮
    private Button backButton;
    
    // 文件列表相关
    private List<File> mdFiles = new ArrayList<>();
    private List<Button> fileButtons = new ArrayList<>();
    private int fileListScrollOffset = 0;
    
    // 文件内容相关
    private List<FormattedCharSequence> fileContentLines = new ArrayList<>();
    private List<MdTitle> mdTitles = new ArrayList<>(); // 用于存储MD文件中的标题和行号
    private int contentScrollOffset = 0;
    
    // 目录相关
    private List<Button> tocButtons = new ArrayList<>();
    private int tocScrollOffset = 0;
    
    // 屏幕尺寸
    private int leftSideWidth;
    private int rightSideWidth;
    private int contentHeight;
    
    /**
     * 构造函数
     */
    public MDRenderScreen() {
        super(Component.translatable("gui.hcrpoints.md_reader.title"));
    }
    
    /**
     * 初始化界面
     */
    @Override
    protected void init() {
        super.init();
        
        // 计算界面布局
        calculateLayout();
        
        // 加载MD文件列表
        loadMDFiles();
        
        // 初始化界面组件
        initFileListUI();
    }
    
    /**
     * 计算界面布局
     */
    private void calculateLayout() {
        // 计算界面布局
        int screenWidth = this.width;
        int screenHeight = this.height;
        
        // 左侧文件列表宽度占屏幕比例
        leftSideWidth = (int) (screenWidth * LEFT_SIDE_RATIO);
        // 右侧内容区域宽度
        rightSideWidth = screenWidth - leftSideWidth;
        // 内容区域高度
        contentHeight = screenHeight - TOP_BAR_HEIGHT;
    }
    
    /**
     * 处理窗口大小变化
     */
    @Override
    public void resize(Minecraft minecraft, int width, int height) {
        super.resize(minecraft, width, height);
        
        // 重新计算布局
        calculateLayout();
        
        // 如果当前是文件内容状态，重新加载文件内容以适应新的窗口大小
        if (currentState == ScreenState.FILE_CONTENT && currentFile != null) {
            loadFileContent(currentFile);
            initFileContentUI();
        } else if (currentState == ScreenState.FILE_LIST) {
            // 如果是文件列表状态，重新初始化UI
            initFileListUI();
        }
    }
    
    /**
     * 初始化文件列表界面组件
     */
    private void initFileListUI() {
        // 清空现有按钮
        fileButtons.clear();
        this.clearWidgets();
        
        // 标题
        this.addRenderableWidget(Button.builder(Component.translatable("gui.hcrpoints.md_reader.file_list"), button -> {})
                .bounds(leftSideWidth / 2 - 50, TOP_BAR_HEIGHT / 2 - 10, 100, 20)
                .build());
        
        // 根据当前滚动偏移量添加文件按钮
        int visibleButtons = (contentHeight - 10) / FILE_LIST_BUTTON_HEIGHT;
        int endIndex = Math.min(fileListScrollOffset + visibleButtons, mdFiles.size());
        for (int i = fileListScrollOffset; i < endIndex; i++) {
            File file = mdFiles.get(i);
            int buttonIndex = i - fileListScrollOffset;
            int buttonY = TOP_BAR_HEIGHT + buttonIndex * FILE_LIST_BUTTON_HEIGHT;
            
            Button fileButton = Button.builder(Component.literal(file.getName()), button -> {
                currentFile = file;
                loadFileContent(file);
                currentState = ScreenState.FILE_CONTENT;
                initFileContentUI();
            })
            .bounds(5, buttonY, leftSideWidth - 10, FILE_LIST_BUTTON_HEIGHT)
            .build();
            
            this.addRenderableWidget(fileButton);
            fileButtons.add(fileButton);
        }
    }
    
    /**
     * 初始化文件内容界面组件
     */
    private void initFileContentUI() {
        this.clearWidgets();
        tocButtons.clear();
        
        // 返回按钮
        backButton = Button.builder(Component.literal("返回列表"), button -> {
            currentState = ScreenState.FILE_LIST;
            initFileListUI();
        })
        .bounds(leftSideWidth / 2 - 40, TOP_BAR_HEIGHT / 2 - 10, 80, 20)
        .build();
        this.addRenderableWidget(backButton);
        
        // 标题
        this.addRenderableWidget(Button.builder(Component.literal(currentFile.getName()), button -> {})
                .bounds(leftSideWidth + rightSideWidth / 2 - 100, TOP_BAR_HEIGHT / 2 - 10, 200, 20)
                .build());
        
        // 生成目录按钮
        generateTocButtons();
    }
    
    /**
     * 加载HCRmdread文件夹中的MD文件
     */
    private void loadMDFiles() {
        // 获取游戏目录
        File minecraftDir = Minecraft.getInstance().gameDirectory;
        Path mdFolderPath = Paths.get(minecraftDir.getAbsolutePath(), MD_FOLDER_NAME);
        File mdFolder = mdFolderPath.toFile();
        
        // 如果文件夹不存在，自动创建
        if (!mdFolder.exists()) {
            boolean created = mdFolder.mkdirs();
            if (created) {
                ModLogger.info("MD文件文件夹已创建: " + mdFolderPath);
            } else {
                ModLogger.error("无法创建MD文件文件夹: " + mdFolderPath);
                return;
            }
        }
        
        // 获取文件夹中的MD文件
        File[] files = mdFolder.listFiles((dir, name) -> name.toLowerCase().endsWith(".md"));
        if (files != null) {
            mdFiles = new ArrayList<>();
            for (File file : files) {
                if (file.isFile()) {
                    mdFiles.add(file);
                }
            }
            ModLogger.info("找到MD文件数量: " + mdFiles.size());
        } else {
            ModLogger.error("无法读取MD文件文件夹内容: " + mdFolderPath);
        }
    }
    
    /**
     * 加载MD文件内容
     * @param file 要加载的MD文件
     */
    private void loadFileContent(File file) {
        try {
            // 读取文件内容
            List<String> lines = Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
            fileContentLines = new ArrayList<>();
            mdTitles = new ArrayList<>();
            
            // 计算内容区域的最大宽度
            int maxContentWidth = width - leftSideWidth - 20;
            
            // 解析MD内容，处理标题和普通文本
            for (int lineIndex = 0; lineIndex < lines.size(); lineIndex++) {
                String line = lines.get(lineIndex);
                // 处理标题
                if (line.startsWith("#")) {
                    // 根据#的数量确定标题级别
                    int level = 0;
                    while (level < line.length() && line.charAt(level) == '#') {
                        level++;
                    }
                    
                    // 标题文本
                    String titleText = line.substring(level).trim();
                    
                    // 为不同级别标题添加不同样式和颜色
                    Component titleComponent;
                    if (level == 1) {
                        titleComponent = Component.literal("=== " + titleText + " ===").withStyle(style -> style.withColor(0xFFFF5555));
                    } else if (level == 2) {
                        titleComponent = Component.literal("--- " + titleText + " ---").withStyle(style -> style.withColor(0xFFFFAA00));
                    } else {
                        titleComponent = Component.literal("   ".repeat(level - 3) + "- " + titleText).withStyle(style -> style.withColor(0xFF55FF55));
                    }
                    
                            // 处理标题行的自动换行
                    String titleLine = titleComponent.getString();
                    processAndAddText(titleLine, maxContentWidth);
                    
                    // 保存标题信息，用于生成目录
                    mdTitles.add(new MdTitle(titleText, level, fileContentLines.size() - 1));
                } else {
                    // 普通文本，添加自动换行和颜色处理
                    processAndAddText(line, maxContentWidth);
                }
            }
            
            // 重置滚动偏移
            contentScrollOffset = 0;
            tocScrollOffset = 0;
            
        } catch (IOException e) {
            ModLogger.error("读取MD文件失败: " + e.getMessage());
            fileContentLines.clear();
            mdTitles.clear();
            fileContentLines.add(Component.literal("无法读取文件内容: " + e.getMessage()).withStyle(style -> style.withColor(0xFFFF5555)).getVisualOrderText());
        }
    }
    
    /**
     * 处理文本，添加自动换行和颜色，并添加到内容列表
     * 使用字符级宽度检测，支持任何语言和字符
     * @param text 要处理的文本
     * @param maxWidth 最大宽度
     */
    private void processAndAddText(String text, int maxWidth) {
        // 处理空行
        if (text.trim().isEmpty()) {
            fileContentLines.add(Component.literal("").getVisualOrderText());
            return;
        }
        
        // 使用字符级宽度检测的自动换行算法
        StringBuilder currentLine = new StringBuilder();
        
        // 遍历文本中的每个字符
        int i = 0;
        while (i < text.length()) {
            // 获取当前字符，处理可能的Unicode补充字符
            int codePoint = text.codePointAt(i);
            int charCount = Character.charCount(codePoint);
            String currentChar = text.substring(i, i + charCount);
            
            // 尝试将当前字符添加到当前行
            String testLine = currentLine + currentChar;
            Component testComponent = addColorToText(testLine);
            int testWidth = this.font.width(testComponent);
            
            // 检查添加当前字符后是否超出最大宽度
            if (testWidth <= maxWidth) {
                // 可以容纳，添加到当前行
                currentLine.append(currentChar);
                i += charCount;
            } else {
                // 超出宽度，换行处理
                if (currentLine.length() > 0) {
                    // 当前行不为空，检查是否有空格可以换行
                    int lastSpaceIndex = currentLine.lastIndexOf(" ");
                    int lastTabIndex = currentLine.lastIndexOf("\t");
                    int breakIndex = Math.max(lastSpaceIndex, lastTabIndex);
                    
                    if (breakIndex > 0) {
                        // 有空格/制表符，在空格/制表符处换行
                        String lineToAdd = currentLine.substring(0, breakIndex);
                        fileContentLines.add(addColorToText(lineToAdd).getVisualOrderText());
                        
                        // 剩余部分作为新行的开始
                        String remainingText = currentLine.substring(breakIndex + 1) + currentChar;
                        currentLine = new StringBuilder(remainingText);
                        i += charCount;
                    } else {
                        // 无空格，直接在当前位置换行
                        fileContentLines.add(addColorToText(currentLine.toString()).getVisualOrderText());
                        currentLine = new StringBuilder(currentChar);
                        i += charCount;
                    }
                } else {
                    // 当前行为空，但单个字符就超出宽度
                    // 这种情况通常不会发生，但为了安全处理
                    fileContentLines.add(addColorToText(currentChar).getVisualOrderText());
                    i += charCount;
                }
            }
        }
        
        // 添加最后一行
        if (currentLine.length() > 0) {
            fileContentLines.add(addColorToText(currentLine.toString()).getVisualOrderText());
        }
    }
    
    /**
     * 根据文本内容添加颜色
     * @param text 要添加颜色的文本
     * @return 带有颜色的Component
     */
    private Component addColorToText(String text) {
        // 根据文本内容添加不同的颜色
        if (text.contains("警告") || text.contains("Warning") || text.contains("WARN")) {
            return Component.literal(text).withStyle(style -> style.withColor(0xFFFFAA00));
        } else if (text.contains("错误") || text.contains("Error") || text.contains("ERR") || text.contains("ERROR")) {
            return Component.literal(text).withStyle(style -> style.withColor(0xFFFF5555));
        } else if (text.contains("提示") || text.contains("Tip") || text.contains("注意") || text.contains("Note")) {
            return Component.literal(text).withStyle(style -> style.withColor(0xFF55FFFF));
        } else if (text.startsWith("- ") || text.startsWith("* ")) {
            return Component.literal(text).withStyle(style -> style.withColor(0xFFAAAAAA));
        } else {
            // 普通文本
            return Component.literal(text).withStyle(style -> style.withColor(0xFFFFFFFF));
        }
    }
    
    /**
     * 渲染界面
     * @param guiGraphics 渲染上下文
     * @param mouseX 鼠标X坐标
     * @param mouseY 鼠标Y坐标
     * @param partialTick 部分tick
     */
    @Override
    public void render(GuiGraphics guiGraphics, int mouseX, int mouseY, float partialTick) {
        // 填充背景
        this.renderBackground(guiGraphics);
        
        // 渲染分割线
        guiGraphics.fill(leftSideWidth - 1, TOP_BAR_HEIGHT, leftSideWidth + 1, height, 0xFFFFFFFF);
        guiGraphics.fill(0, TOP_BAR_HEIGHT - 1, width, TOP_BAR_HEIGHT + 1, 0xFFFFFFFF);
        
        if (currentState == ScreenState.FILE_LIST) {
            renderFileList(guiGraphics);
        } else if (currentState == ScreenState.FILE_CONTENT) {
            renderFileContent(guiGraphics);
        }
        
        super.render(guiGraphics, mouseX, mouseY, partialTick);
    }
    
    /**
     * 渲染文件列表
     * @param guiGraphics 渲染上下文
     */
    private void renderFileList(GuiGraphics guiGraphics) {
        // 渲染文件列表背景
        guiGraphics.fill(0, TOP_BAR_HEIGHT, leftSideWidth, height, 0xFF303030);
        
        // 渲染文件数量
        Component countComponent = Component.literal("共 " + mdFiles.size() + " 个文件");
        guiGraphics.drawString(this.font, countComponent, 5, height - 10, 0xFFFFFFFF);
    }
    
    /**
     * 渲染文件内容
     * @param guiGraphics 渲染上下文
     */
    private void renderFileContent(GuiGraphics guiGraphics) {
        // 渲染内容区域背景
        guiGraphics.fill(leftSideWidth, TOP_BAR_HEIGHT, width, height, 0xFF202020);
        
        // 渲染左侧目录背景
        guiGraphics.fill(0, TOP_BAR_HEIGHT, leftSideWidth, height, 0xFF303030);
        
        // 计算可见行数，充分利用所有空间
        int visibleLines = (height - TOP_BAR_HEIGHT - 10) / LINE_HEIGHT;
        
        // 渲染文件内容
        int startIndex = contentScrollOffset;
        int endIndex = Math.min(contentScrollOffset + visibleLines, fileContentLines.size());
        
        for (int i = startIndex; i < endIndex; i++) {
            int lineIndex = i - startIndex;
            int y = TOP_BAR_HEIGHT + 10 + lineIndex * LINE_HEIGHT;
            
            // 绘制文本
            FormattedCharSequence line = fileContentLines.get(i);
            guiGraphics.drawString(this.font, line, leftSideWidth + 10, y, 0xFFFFFFFF);
        }
    }
    
    /**
     * 生成目录按钮
     */
    private void generateTocButtons() {
        if (mdTitles.isEmpty()) {
            return;
        }
        
        int visibleButtons = (contentHeight - 10) / TOC_BUTTON_HEIGHT;
        int endIndex = Math.min(tocScrollOffset + visibleButtons, mdTitles.size());
        
        for (int i = tocScrollOffset; i < endIndex; i++) {
            MdTitle title = mdTitles.get(i);
            int buttonIndex = i - tocScrollOffset;
            int buttonY = TOP_BAR_HEIGHT + buttonIndex * TOC_BUTTON_HEIGHT;
            
            // 根据标题级别缩进
            int indent = (title.level - 1) * 10;
            
            Button tocButton = Button.builder(Component.literal(title.text), button -> {
                // 跳转到对应行
                contentScrollOffset = title.lineIndex;
            })
            .bounds(5 + indent, buttonY, leftSideWidth - 10 - indent, TOC_BUTTON_HEIGHT)
            .build();
            
            this.addRenderableWidget(tocButton);
            tocButtons.add(tocButton);
        }
    }
    
    /**
     * 处理鼠标滚轮事件
     * @param mouseX 鼠标X坐标
     * @param mouseY 鼠标Y坐标
     * @param delta 滚轮滚动量
     * @return 是否处理了事件
     */
    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double delta) {
        if (currentState == ScreenState.FILE_LIST) {
            // 文件列表滚动
            int visibleButtons = (contentHeight - 10) / FILE_LIST_BUTTON_HEIGHT;
            if (delta > 0) {
                // 向上滚动
                fileListScrollOffset = Math.max(0, fileListScrollOffset - 1);
            } else {
                // 向下滚动
                // 确保不会滚动到超出文件数量的位置
                int maxScroll = Math.max(0, mdFiles.size() - visibleButtons);
                fileListScrollOffset = Math.min(maxScroll, fileListScrollOffset + 1);
            }
            initFileListUI();
            return true;
        } else if (currentState == ScreenState.FILE_CONTENT) {
            // 内容区域滚动
            int visibleLines = (height - TOP_BAR_HEIGHT - 10) / LINE_HEIGHT;
            
            if (mouseX < leftSideWidth) {
                // 左侧目录滚动
                int visibleButtons = (contentHeight - 10) / TOC_BUTTON_HEIGHT;
                if (delta > 0) {
                    // 向上滚动
                    tocScrollOffset = Math.max(0, tocScrollOffset - 1);
                } else {
                    // 向下滚动
                    // 确保不会滚动到超出标题数量的位置
                    int maxScroll = Math.max(0, mdTitles.size() - visibleButtons);
                    tocScrollOffset = Math.min(maxScroll, tocScrollOffset + 1);
                }
                initFileContentUI();
            } else {
                // 右侧内容滚动
                if (delta > 0) {
                    // 向上滚动
                    contentScrollOffset = Math.max(0, contentScrollOffset - 1);
                } else {
                    // 向下滚动
                    contentScrollOffset = Math.max(0, Math.min(fileContentLines.size() - visibleLines, contentScrollOffset + 1));
                }
            }
            return true;
        }
        return super.mouseScrolled(mouseX, mouseY, delta);
    }
    
    /**
     * 处理鼠标点击事件
     * @param mouseX 鼠标X坐标
     * @param mouseY 鼠标Y坐标
     * @param button 鼠标按钮
     * @return 是否处理了事件
     */
    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        return super.mouseClicked(mouseX, mouseY, button);
    }
    
    // 用于存储MD标题和行号
    private static class MdTitle {
        private final String text;
        private final int level;
        private final int lineIndex;
        
        public MdTitle(String text, int level, int lineIndex) {
            this.text = text;
            this.level = level;
            this.lineIndex = lineIndex;
        }
    }
    
    /**
     * 处理键盘按键事件
     * @param keyCode 按键代码
     * @param scanCode 扫描代码
     * @param modifiers 修饰键
     * @return 是否处理了事件
     */
    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        // ESC键返回
        if (keyCode == 256) {
            this.onClose();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
    
    /**
     * 是否暂停游戏
     * @return 是否暂停游戏
     */
    @Override
    public boolean isPauseScreen() {
        return false;
    }
}