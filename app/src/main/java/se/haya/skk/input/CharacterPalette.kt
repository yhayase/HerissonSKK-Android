package se.haya.skk.input

import android.content.Context
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.BaseAdapter
import android.widget.Button
import android.widget.GridView
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.PopupWindow
import android.widget.TextView

/** 分類から文字を選び、元の入力面を変えずに原文の文字列を渡します。 */
internal class CharacterPalette(private val context: Context, private val onSelect: (String) -> Unit) {
    internal var popup: PopupWindow? = null
        private set

    fun show(anchor: View) {
        dismiss()
        if (!anchor.isAttachedToWindow || anchor.windowToken == null) return
        val colors = KeyboardColors.resolve(context)
        val frame = Rect().also(anchor::getWindowVisibleDisplayFrame)
        if (frame.width() <= 0 || frame.height() <= 0) {
            frame.set(0, 0, context.resources.displayMetrics.widthPixels, context.resources.displayMetrics.heightPixels)
        }
        val width = minOf(dp(560), frame.width())
        val height = minOf(dp(360), frame.height())
        val panel = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(4), dp(4), dp(4))
            setBackgroundColor(colors.panelBackground)
        }
        val header = LinearLayout(context).apply { gravity = Gravity.CENTER_VERTICAL }
        header.addView(TextView(context).apply {
            text = "記号・絵文字"
            setTextColor(colors.text)
            textSize = 16f
            setPadding(dp(8), 0, 0, 0)
            accessibilityHeadingCompat()
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.MATCH_PARENT, 1f))
        header.addView(button("閉じる", colors, function = true).apply {
            contentDescription = "記号・絵文字を閉じる"
            setOnClickListener { dismiss() }
        }, LinearLayout.LayoutParams(dp(88), ViewGroup.LayoutParams.MATCH_PARENT))
        panel.addView(header, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))

        val tabs = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }
        panel.addView(HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = true
            addView(tabs)
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52)))
        val grid = GridView(context).apply {
            numColumns = GridView.AUTO_FIT
            columnWidth = dp(52)
            stretchMode = GridView.STRETCH_COLUMN_WIDTH
            horizontalSpacing = dp(2)
            verticalSpacing = dp(2)
            isVerticalScrollBarEnabled = true
            clipToPadding = false
        }
        panel.addView(grid, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        val tabButtons = mutableListOf<Button>()
        fun select(index: Int) {
            val category = CATEGORIES[index]
            grid.adapter = CharacterAdapter(category.entries, colors)
            grid.contentDescription = "${category.label} ${category.entries.size}文字"
            grid.setSelection(0)
            tabButtons.forEachIndexed { i, tab ->
                tab.isSelected = i == index
                tab.background = background(colors, if (i == index) colors.selectedBackground else colors.functionBackground)
            }
        }
        CATEGORIES.forEachIndexed { index, category ->
            val tab = button(category.label, colors, function = true).apply {
                contentDescription = "${category.label} ${category.entries.size}文字"
                setOnClickListener { select(index) }
            }
            tabButtons += tab
            tabs.addView(tab, LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(48)).apply {
                setMargins(dp(2), 0, dp(2), 0)
            })
        }
        select(0)
        val window = PopupWindow(panel, width, height, false).apply {
            setBackgroundDrawable(background(colors, colors.panelBackground))
            isOutsideTouchable = true
            inputMethodMode = PopupWindow.INPUT_METHOD_NOT_NEEDED
            animationStyle = 0
            elevation = dp(4).toFloat()
        }
        popup = window
        window.setOnDismissListener { if (popup === window) popup = null }
        val location = IntArray(2).also(anchor::getLocationOnScreen)
        val left = (location[0] + (anchor.width - width) / 2).coerceIn(frame.left, frame.right - width)
        // IME自身のトークンで表示し、上側の空きが少ない画面ではOSのクリッピングに従います。
        window.showAsDropDown(anchor, left - location[0], -anchor.height - height, Gravity.START)
    }

    fun dismiss() {
        popup?.dismiss()
        popup = null
    }

    private inner class CharacterAdapter(
        private val entries: List<Entry>, private val colors: KeyboardColors,
    ) : BaseAdapter() {
        override fun getCount() = entries.size
        override fun getItem(position: Int) = entries[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convertView: View?, parent: ViewGroup): View {
            val entry = getItem(position)
            return (convertView as? Button ?: button("", colors)).apply {
                text = entry.value
                contentDescription = entry.description
                // GridViewが測定時に設定した内部状態を保ち、先頭候補も正しく接続します。
                val params = layoutParams as? android.widget.AbsListView.LayoutParams
                if (params == null) {
                    layoutParams = android.widget.AbsListView.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(52))
                } else {
                    params.width = ViewGroup.LayoutParams.MATCH_PARENT
                    params.height = dp(52)
                }
                setOnClickListener { onSelect(entry.value) }
            }
        }
    }

    private fun button(label: String, colors: KeyboardColors, function: Boolean = false) = Button(context).apply {
        text = label
        textSize = if (function) 14f else 24f
        isAllCaps = false
        minWidth = if (function) dp(64) else 0
        minHeight = 0
        setPadding(dp(4), 0, dp(4), 0)
        setTextColor(colors.text)
        background = background(colors, if (function) colors.functionBackground else colors.characterBackground)
    }

    private fun background(colors: KeyboardColors, base: Int) = StateListDrawable().apply {
        fun face(color: Int) = GradientDrawable().apply {
            cornerRadius = dp(5).toFloat()
            setColor(color)
            setStroke(dp(1).coerceAtLeast(1), colors.border)
        }
        addState(intArrayOf(android.R.attr.state_pressed), face(colors.pressed))
        addState(intArrayOf(), face(base))
    }

    private fun TextView.accessibilityHeadingCompat() {
        if (android.os.Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
    }

    private fun dp(value: Int) = (value * context.resources.displayMetrics.density).toInt()

    internal data class Entry(val value: String, val description: String = value)
    internal data class Category(val label: String, val entries: List<Entry>)

    internal companion object {
        private val SYMBOL_DESCRIPTIONS = mapOf(
            "!" to "感嘆符", "\"" to "二重引用符", "#" to "番号記号", "$" to "ドル記号",
            "%" to "パーセント", "&" to "アンパサンド", "'" to "一重引用符", "*" to "アスタリスク",
            "+" to "プラス", "," to "コンマ", "-" to "ハイフン", "." to "ピリオド", "/" to "スラッシュ",
            ":" to "コロン", ";" to "セミコロン", "=" to "等号", "?" to "疑問符", "@" to "アットマーク",
            "\\" to "バックスラッシュ", "^" to "キャレット", "_" to "アンダースコア", "`" to "バッククォート",
            "|" to "縦線", "~" to "チルダ", "(" to "左丸括弧", ")" to "右丸括弧",
            "[" to "左角括弧", "]" to "右角括弧", "{" to "左波括弧", "}" to "右波括弧",
            "<" to "小なり", ">" to "大なり", "。" to "句点", "、" to "読点", "・" to "中点",
            "「" to "左かぎ括弧", "」" to "右かぎ括弧", "←" to "左矢印", "↑" to "上矢印",
            "→" to "右矢印", "↓" to "下矢印", "¥" to "円記号", "￥" to "全角円記号",
        )
        private fun symbols(values: String) = values.split(" ").filter(String::isNotEmpty).map {
            Entry(it, SYMBOL_DESCRIPTIONS[it] ?: "記号 $it")
        }
        private fun emoji(vararg values: Pair<String, String>) = values.map { Entry(it.first, it.second) }

        // 複数コードポイントの文字を分割しない、用途別の固定候補です。
        val CATEGORIES = listOf(
            Category("記号", symbols("! \" # $ % & ' * + , - . / : ; = ? @ \\ ^ _ ` | ~ 。 、 ・ ： ； ！ ？ ゛ ゜ ´ ｀ ¨ ＾ ￣ ＿ 〜 … ‥ 〃 々 〆 〇 〒 ※ ☆ ★ ○ ● ◎ ◇ ◆ □ ■ △ ▲ ▽ ▼ ♡ ♥ ♪ ♫ ♭ ♯ ¥ ￥ $ ＄ € £ ¢ ¤ ％ ＆ ＊ ＋ － ／ ＼")),
            Category("括弧", symbols("( ) [ ] { } < > （ ） ［ ］ ｛ ｝ 〈 〉 《 》 「 」 『 』 【 】 〔 〕 〖 〗 〘 〙 〚 〛 “ ” ‘ ’ « »")),
            Category("矢印", symbols("← ↑ → ↓ ↔ ↕ ↖ ↗ ↘ ↙ ⇐ ⇑ ⇒ ⇓ ⇔ ⇕ ↩ ↪ ↶ ↷ ↺ ↻ ⇄ ⇆ ⇧ ⇩ ➜ ➡ ⬅ ⬆ ⬇")),
            Category("数式", symbols("+ − × ÷ = ≠ ≒ ≈ ≡ < > ≤ ≥ ± ∓ ∞ √ ∛ ∑ ∏ ∫ ∮ ∂ ∇ ∈ ∉ ∋ ⊂ ⊃ ⊆ ⊇ ∪ ∩ ∅ ∀ ∃ ∴ ∵ ∠ ⊥ ∥ ° ′ ″ ² ³ ½ ¼ ¾")),
            Category("顔・人", emoji(
                "😀" to "笑顔", "😃" to "大きな笑顔", "😄" to "目を閉じた笑顔", "😁" to "にっこり", "😆" to "笑う顔",
                "😅" to "汗をかいた笑顔", "😂" to "うれし涙", "🤣" to "大笑い", "😊" to "ほほえみ", "😍" to "ハートの目",
                "😘" to "投げキッス", "😎" to "サングラスの顔", "🤔" to "考える顔", "😐" to "無表情", "😴" to "眠る顔",
                "😢" to "泣く顔", "😭" to "大泣き", "😡" to "怒る顔", "😱" to "叫ぶ顔", "😷" to "マスクの顔",
                "👋" to "手を振る", "👏" to "拍手", "👍" to "親指を上げる", "👎" to "親指を下げる", "🙏" to "合わせた手",
                "💪" to "力こぶ", "🙆" to "丸を作る人", "🙅" to "ばつを作る人", "🙇" to "おじぎする人",
                "👩‍💻" to "女性の技術者", "👨‍💻" to "男性の技術者", "👨‍👩‍👧‍👦" to "家族", "👍🏽" to "親指を上げる 中間の肌色",
            )),
            Category("自然・食べ物", emoji(
                "🐶" to "犬", "🐱" to "猫", "🐭" to "ねずみ", "🐰" to "うさぎ", "🦊" to "きつね", "🐻" to "くま",
                "🐼" to "パンダ", "🐨" to "コアラ", "🐯" to "とら", "🦁" to "ライオン", "🐸" to "かえる", "🐵" to "さる",
                "🐧" to "ペンギン", "🐦" to "鳥", "🐤" to "ひよこ", "🐟" to "魚", "🐬" to "イルカ", "🐳" to "くじら",
                "🦋" to "ちょう", "🐝" to "はち", "🌸" to "桜", "🌹" to "ばら", "🌻" to "ひまわり", "🌲" to "木",
                "🍀" to "四つ葉", "🍁" to "もみじ", "☀️" to "太陽", "☁️" to "雲", "☔" to "雨傘", "⛄" to "雪だるま",
                "🌈" to "虹", "🌙" to "月", "⭐" to "星", "🌊" to "波", "🍎" to "りんご", "🍊" to "みかん",
                "🍋" to "レモン", "🍌" to "バナナ", "🍉" to "すいか", "🍇" to "ぶどう", "🍓" to "いちご", "🍑" to "もも",
                "🍞" to "パン", "🍙" to "おにぎり", "🍚" to "ごはん", "🍣" to "すし", "🍜" to "ラーメン", "🍛" to "カレー",
                "🍕" to "ピザ", "🍔" to "ハンバーガー", "🍰" to "ケーキ", "🍩" to "ドーナツ", "🍦" to "ソフトクリーム",
                "☕" to "温かい飲み物", "🍵" to "お茶", "🍺" to "ビール", "🍷" to "ワイン",
            )),
            Category("暮らし", emoji(
                "❤️" to "赤いハート", "💙" to "青いハート", "💚" to "緑のハート", "💛" to "黄色いハート", "💜" to "紫のハート",
                "💔" to "割れたハート", "💕" to "二つのハート", "✨" to "きらめき", "🎉" to "クラッカー", "🎊" to "くす玉",
                "🎁" to "贈り物", "🎂" to "誕生日ケーキ", "🎈" to "風船", "🎄" to "クリスマスツリー", "🎃" to "ハロウィン",
                "⚽" to "サッカー", "⚾" to "野球", "🏀" to "バスケットボール", "🎾" to "テニス", "🏆" to "トロフィー",
                "🎮" to "ゲーム", "🎵" to "音符", "🎸" to "ギター", "🎹" to "ピアノ", "🎬" to "映画",
                "🚗" to "車", "🚕" to "タクシー", "🚌" to "バス", "🚃" to "電車", "🚄" to "新幹線", "🚲" to "自転車",
                "✈️" to "飛行機", "🚀" to "ロケット", "🚢" to "船", "🏠" to "家", "🏢" to "ビル", "🏫" to "学校",
                "🏥" to "病院", "🏪" to "コンビニ", "🗼" to "東京タワー", "🗻" to "富士山", "⌚" to "腕時計",
                "📱" to "携帯電話", "💻" to "パソコン", "📷" to "カメラ", "💡" to "電球", "📚" to "本", "✏️" to "鉛筆",
                "📅" to "カレンダー", "✉️" to "封筒", "📌" to "画びょう", "🔑" to "鍵", "🔒" to "錠前", "🔔" to "ベル",
                "✅" to "チェック", "❌" to "ばつ", "⚠️" to "注意", "❓" to "疑問符", "❗" to "感嘆符", "💯" to "満点",
                "🇯🇵" to "日本の旗", "🇺🇸" to "アメリカの旗", "🇬🇧" to "イギリスの旗", "🇫🇷" to "フランスの旗", "🇩🇪" to "ドイツの旗",
            )),
        )
    }
}
