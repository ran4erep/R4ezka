package com.example

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36])
class ExampleRobolectricTest {

  @Test
  fun `read string from context`() {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val appName = context.getString(R.string.app_name)
    assertEquals("r4ezka", appName)
  }

  @Test
  fun testParseCommentsHtml() {
    val sampleHtml = """
      <ol class="comments-tree-list">
        <li id="comments-tree-item-12345" class="comments-tree-item" data-id="12345" data-indent="0">
          <div id="comment-id-12345" class="b-comment">
            <div class="ava"><img src="//static.hdrezka.ac/avatar.jpg" /></div>
            <div class="info">
              <span class="name"><a href="/user/CinemaFan/">CinemaFan</a></span>
              <span class="date">12 мая 2024, 18:30</span>
            </div>
            <div class="text">
              <div id="comm-id-12345">Шикарный фильм, пересматриваю уже третий раз!</div>
            </div>
            <span class="b-comment__likes_count"><i>42</i></span>
          </div>
        </li>
        <li id="comments-tree-item-12346" class="comments-tree-item" data-id="12346" data-indent="1">
          <div id="comment-id-12346" class="b-comment">
            <div class="info">
              <span class="name">MovieGeek</span>
              <span class="date">12 мая 2024, 19:15</span>
            </div>
            <div class="text">
              <div id="comm-id-12346">Согласен, саундтрек Циммера бесподобен.</div>
            </div>
            <span class="b-comment__likes_count"><i>15</i></span>
          </div>
        </li>
      </ol>
    """.trimIndent()

    val comments = com.example.data.RezkaService.parseCommentsHtml(sampleHtml)
    assertEquals(2, comments.size)

    val first = comments[0]
    assertEquals("12345", first.id)
    assertEquals("CinemaFan", first.author)
    assertEquals("https://static.hdrezka.ac/avatar.jpg", first.avatarUrl)
    assertEquals("12 мая 2024, 18:30", first.date)
    assertEquals("Шикарный фильм, пересматриваю уже третий раз!", first.text)
    assertEquals("42", first.likes)
    assertEquals(0, first.indent)

    val second = comments[1]
    assertEquals("12346", second.id)
    assertEquals("MovieGeek", second.author)
    assertEquals(1, second.indent)
    assertEquals("15", second.likes)
  }
}
