package com.swan1127.repland

import com.swan1127.repland.ui.agent.asksForWholeTeam
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** 群聊路由：问整个团队时不能让某一个人替全员答。 */
class GroupChatTeamIntroTest {
    @Test fun team_worded_capability_questions_broadcast_to_everyone() {
        listOf(
            "你们能做什么",
            "你们都能做什么？",
            "大家都介绍一下自己",
            "每位成员负责什么",
            "这个团队里有谁，各自能干什么",
        ).forEach { assertTrue("$it 应判为全员提问", asksForWholeTeam(it)) }
    }

    @Test fun single_person_and_task_requests_stay_on_the_normal_route() {
        listOf(
            "你能帮我做什么",
            "今天有什么安排",
            "帮我加一个高数作业，明晚做两小时",
            "把英语单词排到早上",
            "小析是谁？不用介绍，直接告诉我还剩多少任务",
            "",
        ).forEach { assertFalse("$it 不应触发全员自我介绍", asksForWholeTeam(it)) }
    }
}
