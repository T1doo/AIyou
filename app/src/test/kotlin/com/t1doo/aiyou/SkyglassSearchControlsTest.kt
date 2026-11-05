package com.t1doo.aiyou

import androidx.compose.foundation.text.input.TextFieldState
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import com.t1doo.aiyou.designsystem.*
import com.t1doo.aiyou.records.LocalOriginalSearchSection
import com.t1doo.aiyou.records.OriginalSearchMode
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],qualifiers="w390dp-h840dp")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class SkyglassSearchControlsTest {
    @get:Rule val compose = createComposeRule()
    @Test fun focusingTypingAndClosingFiltersKeepSearchFieldAnchored() {
        compose.setContent {
            SkyglassTheme(SkyglassAppearance.BLUE) {
                LocalOriginalSearchSection(remember { TextFieldState() },remember { TextFieldState() },remember { TextFieldState() },
                    OriginalSearchMode.KEYWORDS,true,{}, {},indexSupported=true,onQuestionKeywords={},onCategory={})
            }
        }
        val query=compose.onNodeWithTag("original-search-query")
        val before=query.getUnclippedBoundsInRoot()
        query.performClick().performTextInput("合成查找")
        compose.onNodeWithTag("question-keyword-open").assertIsDisplayed()
        val focused=query.getUnclippedBoundsInRoot()
        org.junit.Assert.assertEquals(before.top,focused.top)
        org.junit.Assert.assertEquals(before.left,focused.left)
        org.junit.Assert.assertEquals(before.right,focused.right)
        compose.onNodeWithTag("original-search-filters").performClick()
        compose.onNodeWithTag("original-search-filter-page").assertIsDisplayed()
        compose.onNodeWithTag("original-search-filter-done").performScrollTo().performClick()
        query.assertTextContains("合成查找")
        org.junit.Assert.assertEquals(before.top,query.getUnclippedBoundsInRoot().top)
    }
    @Test fun favoritesRemainIndependentWhenChangingMediaCategory() {
        compose.setContent {
            var favorite by remember { mutableStateOf(false) }
            var category by remember { mutableStateOf("all") }
            SkyglassTheme(SkyglassAppearance.NIGHT) {
                LocalOriginalSearchSection(remember { TextFieldState() },remember { TextFieldState() },remember { TextFieldState() },
                    OriginalSearchMode.KEYWORDS,true,{}, {},favoritesSupported=true,favoritesAvailable=true,
                    onlyFavorites=favorite,onFavorites={favorite=it},onlyImages=category=="images",onlyAudio=category=="audio",
                    onCategory={category=it})
            }
        }
        compose.onNodeWithTag("original-category-favorites").performClick().assertIsSelected()
        compose.onNodeWithTag("original-category-audio").performClick().assertIsSelected()
        compose.onNodeWithTag("original-category-favorites").assertIsSelected()
        compose.onNodeWithTag("original-category-images").performClick().assertIsSelected()
        compose.onNodeWithTag("original-category-audio").assertIsNotSelected()
        compose.onNodeWithTag("original-category-favorites").assertIsSelected().performClick().assertIsNotSelected()
        compose.onNodeWithTag("original-category-images").assertIsSelected()
    }
}
