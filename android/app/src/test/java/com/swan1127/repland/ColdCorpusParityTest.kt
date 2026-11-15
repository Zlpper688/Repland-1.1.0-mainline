package com.swan1127.repland

import com.swan1127.repland.domain.model.RuleIntentKind
import com.swan1127.repland.domain.model.RuleIntentParser
import com.swan1127.repland.domain.model.RuleTask
import java.time.LocalDate
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The Android rule layer must read the 90-case cold corpus exactly like the published
 * web parser: same intent, same questions, same names, dates, clocks, durations,
 * deadlines, weekdays and progress. The snapshot was generated from
 * repland-site/functions/rule-parser.mjs on 2026-11-15.
 */
class ColdCorpusParityTest {
    private val snapshot = """
{"id":"K001","utterance":"明儿一早九点去校医院体检，大概一小时","today":"2026-11-15","tasks":[],"intent":"plan","mustAsk":false,"items":[{"action":"create","name":"一早 去校医院体检","date":"2026-11-16","startMinute":540,"endMinute":null,"durationMinutes":60,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K002","utterance":"周二下午两点半跟导师讨论论文，四十五分钟","today":"2026-11-15","tasks":[],"intent":"plan","mustAsk":false,"items":[{"action":"create","name":"跟导师讨论论文","date":"2026-11-17","startMinute":870,"endMinute":null,"durationMinutes":45,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K003","utterance":"16号上午十点开会，一个半小时","today":"2026-11-15","tasks":[],"intent":"plan","mustAsk":false,"items":[{"action":"create","name":"开会","date":"2026-11-16","startMinute":600,"endMinute":null,"durationMinutes":90,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K004","utterance":"今天傍晚六点半去食堂吃饭","today":"2026-11-15","tasks":[],"intent":"plan","mustAsk":false,"items":[{"action":"create","name":"去食堂吃饭","date":"2026-11-15","startMinute":1110,"endMinute":null,"durationMinutes":60,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K005","utterance":"明晚八点看电影，两个小时","today":"2026-11-15","tasks":[],"intent":"plan","mustAsk":false,"items":[{"action":"create","name":"看电影","date":"2026-11-16","startMinute":1200,"endMinute":null,"durationMinutes":120,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K006","utterance":"后天中午十二点跟队友开短会","today":"2026-11-15","tasks":[],"intent":"plan","mustAsk":false,"items":[{"action":"create","name":"跟队友开短会","date":"2026-11-17","startMinute":720,"endMinute":null,"durationMinutes":60,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K007","utterance":"下周一早上八点半到九点背单词","today":"2026-11-15","tasks":[],"intent":"plan","mustAsk":false,"items":[{"action":"create","name":"背单词","date":"2026-11-16","startMinute":510,"endMinute":540,"durationMinutes":30,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K008","utterance":"20号下午四点前交实验报告","today":"2026-11-15","tasks":[],"intent":"plan","mustAsk":false,"items":[{"action":"create","name":"交实验报告","date":null,"startMinute":null,"endMinute":null,"durationMinutes":null,"dueDate":"2026-11-20","dueMinute":960,"weekdays":null,"progress":null}]}
{"id":"K009","utterance":"11月18日晚上七点到九点练琴","today":"2026-11-15","tasks":[],"intent":"plan","mustAsk":false,"items":[{"action":"create","name":"练琴","date":"2026-11-18","startMinute":1140,"endMinute":1260,"durationMinutes":120,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K010","utterance":"这周六上午十点去菜鸟驿站取快递","today":"2026-11-15","tasks":[],"intent":"plan","mustAsk":false,"items":[{"action":"create","name":"去菜鸟驿站取快递","date":"2026-11-21","startMinute":600,"endMinute":null,"durationMinutes":30,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K011","utterance":"明天上午十一点半吃饭，半小时","today":"2026-11-15","tasks":[],"intent":"plan","mustAsk":false,"items":[{"action":"create","name":"吃饭","date":"2026-11-16","startMinute":690,"endMinute":null,"durationMinutes":30,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K012","utterance":"今晚九点跑步四十分钟","today":"2026-11-15","tasks":[],"intent":"plan","mustAsk":false,"items":[{"action":"create","name":"跑步","date":"2026-11-15","startMinute":1260,"endMinute":null,"durationMinutes":40,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K013","utterance":"周五下午五点前把周报发出去","today":"2026-11-15","tasks":[],"intent":"plan","mustAsk":false,"items":[{"action":"create","name":"发周报","date":null,"startMinute":null,"endMinute":null,"durationMinutes":null,"dueDate":"2026-11-20","dueMinute":1020,"weekdays":null,"progress":null}]}
{"id":"K014","utterance":"明天下午两点开始写代码，写到五点","today":"2026-11-15","tasks":[],"intent":"plan","mustAsk":false,"items":[{"action":"create","name":"写代码","date":"2026-11-16","startMinute":840,"endMinute":1020,"durationMinutes":180,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K015","utterance":"下周四早上七点二十赶高铁","today":"2026-11-15","tasks":[],"intent":"plan","mustAsk":false,"items":[{"action":"create","name":"赶高铁","date":"2026-11-19","startMinute":440,"endMinute":null,"durationMinutes":60,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K016","utterance":"17号上午九点陪妈妈去医院复查","today":"2026-11-15","tasks":[],"intent":"plan","mustAsk":false,"items":[{"action":"create","name":"陪妈妈去医院复查","date":"2026-11-17","startMinute":540,"endMinute":null,"durationMinutes":60,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K017","utterance":"后天下午三点到四点半做实验","today":"2026-11-15","tasks":[],"intent":"plan","mustAsk":false,"items":[{"action":"create","name":"做实验","date":"2026-11-17","startMinute":900,"endMinute":990,"durationMinutes":90,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K018","utterance":"今晚七点半到八点看新闻","today":"2026-11-15","tasks":[],"intent":"plan","mustAsk":false,"items":[{"action":"create","name":"看新闻","date":"2026-11-15","startMinute":1170,"endMinute":1200,"durationMinutes":30,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K019","utterance":"11月22号下午两点开会，两小时","today":"2026-11-15","tasks":[],"intent":"plan","mustAsk":false,"items":[{"action":"create","name":"开会","date":"2026-11-22","startMinute":840,"endMinute":null,"durationMinutes":120,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K020","utterance":"明天中午吃完饭后去超市买水果","today":"2026-11-15","tasks":[],"intent":"plan","mustAsk":false,"items":[{"action":"create","name":"中午 去超市买水果","date":"2026-11-16","startMinute":null,"endMinute":null,"durationMinutes":30,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K021","utterance":"帮我记一下，给实验室买鼠标","today":"2026-11-15","tasks":[],"intent":"plan","mustAsk":false,"items":[{"action":"create","name":"给实验室买鼠标","date":null,"startMinute":null,"endMinute":null,"durationMinutes":null,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K022","utterance":"提醒我周三交课程论文","today":"2026-11-15","tasks":[],"intent":"plan","mustAsk":false,"items":[{"action":"create","name":"提醒我 交课程论文","date":"2026-11-18","startMinute":null,"endMinute":null,"durationMinutes":60,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K023","utterance":"明天要交英语作业","today":"2026-11-15","tasks":[],"intent":"plan","mustAsk":false,"items":[{"action":"create","name":"要交英语作业","date":"2026-11-16","startMinute":null,"endMinute":null,"durationMinutes":60,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K024","utterance":"找个时间把房间收拾一下","today":"2026-11-15","tasks":[],"intent":"plan","mustAsk":false,"items":[{"action":"create","name":"收拾房间","date":null,"startMinute":null,"endMinute":null,"durationMinutes":60,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K025","utterance":"下周末去爬山","today":"2026-11-15","tasks":[],"intent":"clarify","mustAsk":true,"items":[]}
{"id":"K026","utterance":"最近得写个总结","today":"2026-11-15","tasks":[],"intent":"plan","mustAsk":false,"items":[{"action":"create","name":"得写总结","date":null,"startMinute":null,"endMinute":null,"durationMinutes":60,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K027","utterance":"帮我加一项：练口语","today":"2026-11-15","tasks":[],"intent":"plan","mustAsk":false,"items":[{"action":"create","name":"加一项:练口语","date":null,"startMinute":null,"endMinute":null,"durationMinutes":30,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K028","utterance":"周四下午交课程设计","today":"2026-11-15","tasks":[],"intent":"plan","mustAsk":false,"items":[{"action":"create","name":"下午交课程设计","date":"2026-11-19","startMinute":null,"endMinute":null,"durationMinutes":60,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K029","utterance":"把明早的跑步挪到傍晚六点","today":"2026-11-15","tasks":[{"id":"seed-1","name":"跑步","durationMinutes":60,"date":"2026-11-16"}],"intent":"plan","mustAsk":false,"items":[{"action":"schedule","name":"跑步","date":"2026-11-16","startMinute":1080,"endMinute":null,"durationMinutes":60,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K030","utterance":"数学作业改到后天晚上八点","today":"2026-11-15","tasks":[{"id":"seed-1","name":"数学作业","durationMinutes":60,"date":"2026-11-16"}],"intent":"plan","mustAsk":false,"items":[{"action":"schedule","name":"数学作业","date":"2026-11-17","startMinute":1200,"endMinute":null,"durationMinutes":60,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K031","utterance":"组会从周三下午两点改到周四上午十点","today":"2026-11-15","tasks":[{"id":"seed-1","name":"组会","durationMinutes":60,"date":"2026-11-18"}],"intent":"plan","mustAsk":false,"items":[{"action":"schedule","name":"组会","date":"2026-11-19","startMinute":600,"endMinute":null,"durationMinutes":60,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K032","utterance":"实验报告推后到周五下午三点再写","today":"2026-11-15","tasks":[{"id":"seed-1","name":"实验报告","durationMinutes":120,"date":"2026-11-16"}],"intent":"plan","mustAsk":false,"items":[{"action":"schedule","name":"实验报告","date":"2026-11-20","startMinute":900,"endMinute":null,"durationMinutes":120,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K033","utterance":"明天下午的会议提前到上午十点","today":"2026-11-15","tasks":[{"id":"seed-1","name":"会议","durationMinutes":60,"date":"2026-11-16"}],"intent":"plan","mustAsk":false,"items":[{"action":"schedule","name":"会议","date":"2026-11-16","startMinute":600,"endMinute":null,"durationMinutes":60,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K034","utterance":"把练吉他从今晚改到明晚八点","today":"2026-11-15","tasks":[{"id":"seed-1","name":"练吉他","durationMinutes":45,"date":"2026-11-15"}],"intent":"plan","mustAsk":false,"items":[{"action":"schedule","name":"练吉他","date":"2026-11-16","startMinute":1200,"endMinute":null,"durationMinutes":45,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K035","utterance":"周三的组会调到周五下午四点","today":"2026-11-15","tasks":[{"id":"seed-1","name":"组会","durationMinutes":60,"date":"2026-11-18"}],"intent":"plan","mustAsk":false,"items":[{"action":"schedule","name":"组会","date":"2026-11-20","startMinute":960,"endMinute":null,"durationMinutes":60,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K036","utterance":"取快递改明天中午","today":"2026-11-15","tasks":[{"id":"seed-1","name":"取快递","durationMinutes":30,"date":"2026-11-15"}],"intent":"plan","mustAsk":false,"items":[{"action":"schedule","name":"取快递","date":"2026-11-16","startMinute":720,"endMinute":null,"durationMinutes":30,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K037","utterance":"高数复习换到明早七点","today":"2026-11-15","tasks":[{"id":"seed-1","name":"高数复习","durationMinutes":90,"date":"2026-11-15"}],"intent":"plan","mustAsk":false,"items":[{"action":"schedule","name":"高数复习","date":"2026-11-16","startMinute":420,"endMinute":null,"durationMinutes":90,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K038","utterance":"把明天的跑步改到六点半","today":"2026-11-15","tasks":[{"id":"seed-1","name":"跑步","durationMinutes":60,"date":"2026-11-16"}],"intent":"plan","mustAsk":false,"items":[{"action":"schedule","name":"跑步","date":"2026-11-16","startMinute":390,"endMinute":null,"durationMinutes":60,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K039","utterance":"实验报告交上去了","today":"2026-11-15","tasks":[{"id":"seed-1","name":"实验报告","durationMinutes":120,"date":"2026-11-15"}],"intent":"plan","mustAsk":false,"items":[{"action":"complete","name":"实验报告","date":null,"startMinute":null,"endMinute":null,"durationMinutes":null,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K040","utterance":"高数复习完了，做了三套卷子","today":"2026-11-15","tasks":[{"id":"seed-1","name":"高数复习","durationMinutes":90,"date":"2026-11-15"}],"intent":"plan","mustAsk":false,"items":[{"action":"complete","name":"高数复习","date":null,"startMinute":null,"endMinute":null,"durationMinutes":null,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K041","utterance":"今天的跑步取消了","today":"2026-11-15","tasks":[{"id":"seed-1","name":"跑步","durationMinutes":60,"date":"2026-11-15"}],"intent":"plan","mustAsk":false,"items":[{"action":"cancel","name":"跑步","date":null,"startMinute":null,"endMinute":null,"durationMinutes":null,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K042","utterance":"组会先不开了","today":"2026-11-15","tasks":[{"id":"seed-1","name":"组会","durationMinutes":60,"date":"2026-11-15"}],"intent":"plan","mustAsk":false,"items":[{"action":"cancel","name":"组会","date":null,"startMinute":null,"endMinute":null,"durationMinutes":null,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K043","utterance":"论文写到三分之二了","today":"2026-11-15","tasks":[{"id":"seed-1","name":"论文","durationMinutes":120,"date":"2026-11-15"}],"intent":"plan","mustAsk":false,"items":[{"action":"progress","name":"论文","date":null,"startMinute":null,"endMinute":null,"durationMinutes":null,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":67}]}
{"id":"K044","utterance":"背单词背了四十分钟，才背了一半","today":"2026-11-15","tasks":[{"id":"seed-1","name":"背单词","durationMinutes":30,"date":"2026-11-15"}],"intent":"plan","mustAsk":false,"items":[{"action":"progress","name":"背单词","date":null,"startMinute":null,"endMinute":null,"durationMinutes":null,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":50}]}
{"id":"K045","utterance":"蓝桥杯报名没搞定，再往后拖两天","today":"2026-11-15","tasks":[{"id":"seed-1","name":"报名蓝桥杯","durationMinutes":30,"date":"2026-11-16"}],"intent":"plan","mustAsk":false,"items":[{"action":"postpone","name":"报名蓝桥杯","date":"2026-11-18","startMinute":null,"endMinute":null,"durationMinutes":null,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K046","utterance":"项目讨论推迟到周五","today":"2026-11-15","tasks":[{"id":"seed-1","name":"项目讨论","durationMinutes":60,"date":"2026-11-16"}],"intent":"plan","mustAsk":false,"items":[{"action":"postpone","name":"项目讨论","date":"2026-11-20","startMinute":null,"endMinute":null,"durationMinutes":null,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K047","utterance":"今天的实验报告没写完","today":"2026-11-15","tasks":[{"id":"seed-1","name":"实验报告","durationMinutes":120,"date":"2026-11-15"}],"intent":"plan","mustAsk":false,"items":[{"action":"progress","name":"实验报告","date":null,"startMinute":null,"endMinute":null,"durationMinutes":null,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null},{"action":"create","name":"实验报告","date":"2026-11-15","startMinute":null,"endMinute":null,"durationMinutes":null,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K048","utterance":"练吉他练到一半被打断了","today":"2026-11-15","tasks":[{"id":"seed-1","name":"练吉他","durationMinutes":45,"date":"2026-11-15"}],"intent":"plan","mustAsk":false,"items":[{"action":"progress","name":"练吉他","date":null,"startMinute":null,"endMinute":null,"durationMinutes":null,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":50}]}
{"id":"K049","utterance":"交作业的事已经搞定了","today":"2026-11-15","tasks":[{"id":"seed-1","name":"交作业","durationMinutes":60,"date":"2026-11-15"}],"intent":"plan","mustAsk":false,"items":[{"action":"complete","name":"交作业","date":null,"startMinute":null,"endMinute":null,"durationMinutes":null,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K050","utterance":"组会取消吧","today":"2026-11-15","tasks":[{"id":"seed-1","name":"组会","durationMinutes":60,"date":"2026-11-15"}],"intent":"plan","mustAsk":false,"items":[{"action":"cancel","name":"组会","date":null,"startMinute":null,"endMinute":null,"durationMinutes":null,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K051","utterance":"报告没写完，明天再给我一个半小时","today":"2026-11-15","tasks":[{"id":"seed-1","name":"报告","durationMinutes":120,"date":"2026-11-15"}],"intent":"plan","mustAsk":false,"items":[{"action":"progress","name":"报告","date":null,"startMinute":null,"endMinute":null,"durationMinutes":null,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null},{"action":"create","name":"报告","date":"2026-11-16","startMinute":null,"endMinute":null,"durationMinutes":90,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K052","utterance":"实验只做了一半，明天继续，留两个小时","today":"2026-11-15","tasks":[{"id":"seed-1","name":"实验","durationMinutes":120,"date":"2026-11-15"}],"intent":"plan","mustAsk":false,"items":[{"action":"progress","name":"实验","date":null,"startMinute":null,"endMinute":null,"durationMinutes":null,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":50},{"action":"create","name":"实验","date":"2026-11-16","startMinute":null,"endMinute":null,"durationMinutes":120,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K053","utterance":"单词没背完，后天补四十分钟","today":"2026-11-15","tasks":[{"id":"seed-1","name":"单词","durationMinutes":30,"date":"2026-11-15"}],"intent":"plan","mustAsk":false,"items":[{"action":"progress","name":"单词","date":null,"startMinute":null,"endMinute":null,"durationMinutes":null,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null},{"action":"create","name":"单词","date":"2026-11-17","startMinute":null,"endMinute":null,"durationMinutes":40,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K054","utterance":"数学卷子做到一半，今晚再给半小时","today":"2026-11-15","tasks":[{"id":"seed-1","name":"数学卷子","durationMinutes":60,"date":"2026-11-15"}],"intent":"plan","mustAsk":false,"items":[{"action":"progress","name":"数学卷子","date":null,"startMinute":null,"endMinute":null,"durationMinutes":null,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":50},{"action":"create","name":"数学卷子","date":"2026-11-15","startMinute":null,"endMinute":null,"durationMinutes":30,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K055","utterance":"代码没写完，明天上午再排两小时","today":"2026-11-15","tasks":[{"id":"seed-1","name":"代码","durationMinutes":90,"date":"2026-11-15"}],"intent":"plan","mustAsk":false,"items":[{"action":"progress","name":"代码","date":null,"startMinute":null,"endMinute":null,"durationMinutes":null,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null},{"action":"create","name":"代码","date":"2026-11-16","startMinute":null,"endMinute":null,"durationMinutes":120,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K056","utterance":"论文只写了个开头，明天再给一小时","today":"2026-11-15","tasks":[{"id":"seed-1","name":"论文","durationMinutes":120,"date":"2026-11-15"}],"intent":"plan","mustAsk":false,"items":[{"action":"progress","name":"论文","date":null,"startMinute":null,"endMinute":null,"durationMinutes":null,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null},{"action":"create","name":"论文","date":"2026-11-16","startMinute":null,"endMinute":null,"durationMinutes":60,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K057","utterance":"这周的报告没交，明天补上","today":"2026-11-15","tasks":[{"id":"seed-1","name":"报告","durationMinutes":120,"date":"2026-11-15"}],"intent":"plan","mustAsk":false,"items":[{"action":"progress","name":"报告","date":null,"startMinute":null,"endMinute":null,"durationMinutes":null,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null},{"action":"create","name":"报告","date":"2026-11-16","startMinute":null,"endMinute":null,"durationMinutes":null,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K058","utterance":"实验报告没做完，明天下午再给我两个小时","today":"2026-11-15","tasks":[{"id":"seed-1","name":"实验报告","durationMinutes":120,"date":"2026-11-15"}],"intent":"plan","mustAsk":false,"items":[{"action":"progress","name":"实验报告","date":null,"startMinute":null,"endMinute":null,"durationMinutes":null,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null},{"action":"create","name":"实验报告","date":"2026-11-16","startMinute":null,"endMinute":null,"durationMinutes":120,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K059","utterance":"练琴没练完，明晚再排四十五分钟","today":"2026-11-15","tasks":[{"id":"seed-1","name":"练琴","durationMinutes":45,"date":"2026-11-15"}],"intent":"plan","mustAsk":false,"items":[{"action":"progress","name":"练琴","date":null,"startMinute":null,"endMinute":null,"durationMinutes":null,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null},{"action":"create","name":"练琴","date":"2026-11-16","startMinute":1200,"endMinute":null,"durationMinutes":45,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K060","utterance":"高数只复习了一半，明天再做一套题","today":"2026-11-15","tasks":[{"id":"seed-1","name":"高数","durationMinutes":90,"date":"2026-11-15"}],"intent":"plan","mustAsk":false,"items":[{"action":"progress","name":"高数","date":null,"startMinute":null,"endMinute":null,"durationMinutes":null,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":50},{"action":"create","name":"高数","date":"2026-11-16","startMinute":null,"endMinute":null,"durationMinutes":null,"dueDate":null,"dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K061","utterance":"每天早上六点半跑五公里","today":"2026-11-15","tasks":[],"intent":"plan","mustAsk":false,"items":[{"action":"routine","name":"跑五公里","date":null,"startMinute":390,"endMinute":null,"durationMinutes":30,"dueDate":null,"dueMinute":null,"weekdays":[1,2,3,4,5,6,7],"progress":null}]}
{"id":"K062","utterance":"周一到周五中午十二点吃饭，一小时","today":"2026-11-15","tasks":[],"intent":"plan","mustAsk":false,"items":[{"action":"routine","name":"吃饭","date":null,"startMinute":720,"endMinute":null,"durationMinutes":60,"dueDate":null,"dueMinute":null,"weekdays":[1,2,3,4,5],"progress":null}]}
{"id":"K063","utterance":"每周二四六晚上八点练吉他","today":"2026-11-15","tasks":[],"intent":"plan","mustAsk":false,"items":[{"action":"routine","name":"练吉他","date":null,"startMinute":1200,"endMinute":null,"durationMinutes":30,"dueDate":null,"dueMinute":null,"weekdays":[2,4,6],"progress":null}]}
{"id":"K064","utterance":"工作日上午十点背单词半小时","today":"2026-11-15","tasks":[],"intent":"plan","mustAsk":false,"items":[{"action":"routine","name":"背单词","date":null,"startMinute":600,"endMinute":null,"durationMinutes":30,"dueDate":null,"dueMinute":null,"weekdays":[1,2,3,4,5],"progress":null}]}
{"id":"K065","utterance":"每个周末上午九点去图书馆","today":"2026-11-15","tasks":[],"intent":"plan","mustAsk":false,"items":[{"action":"routine","name":"每个 去图书馆","date":null,"startMinute":540,"endMinute":null,"durationMinutes":60,"dueDate":null,"dueMinute":null,"weekdays":[6,7],"progress":null}]}
{"id":"K066","utterance":"每周三下午两点组会，一个半小时","today":"2026-11-15","tasks":[],"intent":"plan","mustAsk":false,"items":[{"action":"routine","name":"组会","date":null,"startMinute":840,"endMinute":null,"durationMinutes":90,"dueDate":null,"dueMinute":null,"weekdays":[3],"progress":null}]}
{"id":"K067","utterance":"每天睡前看半小时书","today":"2026-11-15","tasks":[],"intent":"plan","mustAsk":false,"items":[{"action":"routine","name":"看 书","date":null,"startMinute":null,"endMinute":null,"durationMinutes":30,"dueDate":null,"dueMinute":null,"weekdays":[1,2,3,4,5,6,7],"progress":null}]}
{"id":"K068","utterance":"每周一早上七点升国旗","today":"2026-11-15","tasks":[],"intent":"plan","mustAsk":false,"items":[{"action":"routine","name":"升国旗","date":null,"startMinute":420,"endMinute":null,"durationMinutes":60,"dueDate":null,"dueMinute":null,"weekdays":[1],"progress":null}]}
{"id":"K069","utterance":"周二和周四晚上七点健身一小时","today":"2026-11-15","tasks":[],"intent":"plan","mustAsk":false,"items":[{"action":"routine","name":"健身","date":null,"startMinute":1140,"endMinute":null,"durationMinutes":60,"dueDate":null,"dueMinute":null,"weekdays":[2,4],"progress":null}]}
{"id":"K070","utterance":"从下周一开始每天练口语二十分钟","today":"2026-11-15","tasks":[],"intent":"plan","mustAsk":false,"items":[{"action":"routine","name":"练口语","date":null,"startMinute":null,"endMinute":null,"durationMinutes":20,"dueDate":null,"dueMinute":null,"weekdays":[1,2,3,4,5,6,7],"progress":null}]}
{"id":"K071","utterance":"下周五前把开题报告写完","today":"2026-11-15","tasks":[],"intent":"plan","mustAsk":false,"items":[{"action":"create","name":"写开题报告","date":null,"startMinute":null,"endMinute":null,"durationMinutes":null,"dueDate":"2026-11-20","dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K072","utterance":"月底前交房租","today":"2026-11-15","tasks":[],"intent":"plan","mustAsk":false,"items":[{"action":"create","name":"交房租","date":null,"startMinute":null,"endMinute":null,"durationMinutes":null,"dueDate":"2026-11-30","dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K073","utterance":"12月1号之前报名四级","today":"2026-11-15","tasks":[],"intent":"plan","mustAsk":false,"items":[{"action":"create","name":"报名四级","date":null,"startMinute":null,"endMinute":null,"durationMinutes":null,"dueDate":"2026-12-01","dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K074","utterance":"周五下午三点前提交表单","today":"2026-11-15","tasks":[],"intent":"plan","mustAsk":false,"items":[{"action":"create","name":"提交表单","date":null,"startMinute":null,"endMinute":null,"durationMinutes":null,"dueDate":"2026-11-20","dueMinute":900,"weekdays":null,"progress":null}]}
{"id":"K075","utterance":"明天上午十点前把材料发给老师","today":"2026-11-15","tasks":[],"intent":"plan","mustAsk":false,"items":[{"action":"create","name":"发材料","date":null,"startMinute":null,"endMinute":null,"durationMinutes":null,"dueDate":"2026-11-16","dueMinute":600,"weekdays":null,"progress":null}]}
{"id":"K076","utterance":"11.25前完成课程设计","today":"2026-11-15","tasks":[],"intent":"plan","mustAsk":false,"items":[{"action":"create","name":"课程设计","date":null,"startMinute":null,"endMinute":null,"durationMinutes":null,"dueDate":"2026-11-25","dueMinute":null,"weekdays":null,"progress":null}]}
{"id":"K077","utterance":"我周三下午有空吗","today":"2026-11-15","tasks":[],"intent":"query","mustAsk":false,"items":[]}
{"id":"K078","utterance":"这周还剩几个小时","today":"2026-11-15","tasks":[],"intent":"query","mustAsk":false,"items":[]}
{"id":"K079","utterance":"今天还有什么没做的","today":"2026-11-15","tasks":[],"intent":"query","mustAsk":false,"items":[]}
{"id":"K080","utterance":"好累啊不想动","today":"2026-11-15","tasks":[],"intent":"query","mustAsk":false,"items":[]}
{"id":"K081","utterance":"先做哪件事比较好","today":"2026-11-15","tasks":[],"intent":"query","mustAsk":false,"items":[]}
{"id":"K082","utterance":"明天的时间怎么安排比较好","today":"2026-11-15","tasks":[],"intent":"query","mustAsk":false,"items":[]}
{"id":"K083","utterance":"如果明天下雨就取消跑步","today":"2026-11-15","tasks":[],"intent":"clarify","mustAsk":true,"items":[]}
{"id":"K084","utterance":"把这个挪到后天","today":"2026-11-15","tasks":[],"intent":"clarify","mustAsk":true,"items":[]}
{"id":"K085","utterance":"随便排就行","today":"2026-11-15","tasks":[],"intent":"clarify","mustAsk":true,"items":[]}
{"id":"K086","utterance":"tomorrow 3pm meeting","today":"2026-11-15","tasks":[],"intent":"clarify","mustAsk":true,"items":[]}
{"id":"K087","utterance":"下午两点","today":"2026-11-15","tasks":[],"intent":"clarify","mustAsk":true,"items":[]}
{"id":"K088","utterance":"明天上午十点开会，然后下午三点写报告","today":"2026-11-15","tasks":[],"intent":"clarify","mustAsk":true,"items":[]}
{"id":"K089","utterance":"明早八点跑步，顺便取快递","today":"2026-11-15","tasks":[],"intent":"clarify","mustAsk":true,"items":[]}
{"id":"K090","utterance":"后天交作业，晚上八点打球两小时","today":"2026-11-15","tasks":[],"intent":"clarify","mustAsk":true,"items":[]}
    """.trimIndent()

    @Test
    fun android_reads_every_cold_case_like_the_published_web_rule_layer() {
        val failures = mutableListOf<String>()
        snapshot.lineSequence().filter { it.isNotBlank() }.forEach { line ->
            val case = JSONObject(line)
            val id = case.getString("id")
            val today = LocalDate.parse(case.getString("today"))
            val tasksJson = case.getJSONArray("tasks")
            val tasks = (0 until tasksJson.length()).map { index ->
                val task = tasksJson.getJSONObject(index)
                RuleTask(task.getString("id"), task.getString("name"),
                    if (task.isNull("durationMinutes")) null else task.getInt("durationMinutes"),
                    if (task.isNull("date")) null else LocalDate.parse(task.getString("date")))
            }
            val plan = RuleIntentParser.parse(case.getString("utterance"), today, tasks)
            val expectedIntent = when (case.getString("intent")) {
                "query" -> RuleIntentKind.QUERY
                "clarify" -> RuleIntentKind.CLARIFY
                else -> RuleIntentKind.PLAN
            }
            if (plan.intent != expectedIntent) failures += "$id intent ${plan.intent} != ${expectedIntent}"
            if (plan.mustAsk != case.getBoolean("mustAsk")) failures += "$id mustAsk ${plan.mustAsk} != ${case.getBoolean("mustAsk")}"
            if (case.getBoolean("mustAsk")) return@forEach
            val expected = case.getJSONArray("items")
            if (plan.draftItems.size != expected.length()) {
                failures += "$id items ${plan.draftItems.size} != ${expected.length()}"
                return@forEach
            }
            for (index in 0 until expected.length()) {
                val want = expected.getJSONObject(index)
                val got = plan.draftItems[index]
                val prefix = "$id[$index]"
                fun check(field: String, wanted: Any?, actual: Any?) {
                    if (wanted != actual) failures += "$prefix $field $actual != $wanted"
                }
                check("action", want.getString("action"), got.action.name.lowercase())
                check("name", want.getString("name").replace(Regex("\\s+"), ""), got.name.replace(Regex("\\s+"), ""))
                check("date", want.nullable("date"), got.date?.toString())
                check("start", want.nullableInt("startMinute"), got.startMinute)
                check("end", want.nullableInt("endMinute"), got.endMinute)
                check("duration", want.nullableInt("durationMinutes"), got.durationMinutes)
                check("dueDate", want.nullable("dueDate"), got.dueDate?.toString())
                check("dueMinute", want.nullableInt("dueMinute"), got.dueMinute)
                val wantDays = if (want.isNull("weekdays")) null else
                    (0 until want.getJSONArray("weekdays").length()).map { want.getJSONArray("weekdays").getInt(it) }
                check("weekdays", wantDays, got.weekdays)
                check("progress", want.nullableInt("progress"), got.progress)
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    private fun JSONObject.nullable(key: String): String? = if (isNull(key)) null else getString(key)
    private fun JSONObject.nullableInt(key: String): Int? = if (isNull(key)) null else getInt(key)
}
