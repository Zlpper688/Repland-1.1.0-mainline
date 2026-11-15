package com.swan1127.repland.domain.team

import java.time.LocalDate

/**
 * 竞赛环节建模（TEAM_COLLAB_DESIGN.md §4）：
 * 一场比赛不是一个截止日，而是一串有交付物、有检查点、有过关要求的环节。
 * 这里把「报名 → 准备 → 攻关 → 提交 → 答辩」固化成可实例化的模板；
 * 队长套模板得到阶段与基线任务，仍可逐项改，模板只是起点不是约束。
 */

enum class StageKind(val label: String) {
    SIGNUP("报名组队"),
    PREP("赛前准备"),
    BUILD("攻关制作"),
    SOLVE("集中解题"),
    WRITE("成文提交"),
    REVIEW("评审打磨"),
    DEFENSE("答辩路演"),
    ARCHIVE("复盘归档"),
    CUSTOM("自定义"),
}

/** 带日期的关键时点（由 [Phase.moments] 展开），供提醒与时间线使用。 */
data class StageMomentAt(
    val phaseId: String,
    val phaseName: String,
    val date: LocalDate,
    val label: String,
    val hard: Boolean,
)

/** 模板里的一个基线任务；[dependsOn] 指同阶段内第几个蓝图任务（上游先排）。 */
data class StageTaskBlueprint(
    val title: String,
    val hours: Double,
    val tier: TaskTier = TaskTier.MID_TERM,
    val rank: TaskRank = TaskRank.SHOULD,
    val dependsOn: List<Int> = emptyList(),
    /** 关卡动作（如最终提交）要等上一环节全部交付才开工。 */
    val dependsOnPreviousStage: Boolean = false,
)

data class StageBlueprint(
    val kind: StageKind,
    val name: String,
    val days: Int,
    val deliverables: List<String> = emptyList(),
    val checkpoints: List<String> = emptyList(),
    val moments: List<StageMoment> = emptyList(),
    val isGate: Boolean = false,
    val tasks: List<StageTaskBlueprint> = emptyList(),
) {
    init {
        require(days >= 1) { "环节「$name」至少 1 天" }
        moments.forEach { moment ->
            require(moment.offsetDays in 0 until days) { "「$name」的时点 ${moment.label} 超出环节窗口" }
        }
        tasks.forEachIndexed { index, task ->
            task.dependsOn.forEach { dep ->
                require(dep in 0 until index) { "「${task.title}」只能依赖排在它前面的任务" }
            }
        }
    }
}

data class StageTemplate(
    val id: String,
    val label: String,
    val stages: List<StageBlueprint>,
) {
    init {
        require(stages.isNotEmpty()) { "模板至少要有一个环节" }
    }
}

data class StagePlan(
    val template: StageTemplate,
    val phases: List<Phase>,
    val tasks: List<TeamTask>,
    val moments: List<StageMomentAt> = emptyList(),
)

object CompetitionStages {

    /** 数模赛：报名 → 准备 → 3 天集中解题 → 成文提交 → 答辩（如有）。 */
    val mathModeling = StageTemplate(
        id = "math_modeling",
        label = "数模赛（3 天集中赛）",
        stages = listOf(
            StageBlueprint(
                kind = StageKind.SIGNUP,
                name = "报名组队",
                days = 7,
                deliverables = listOf("报名信息", "队号与赛题类型"),
                checkpoints = listOf("报名截止日", "确认人数上限"),
                moments = listOf(StageMoment("报名截止", offsetDays = 6, hard = true)),
                tasks = listOf(
                    StageTaskBlueprint("报名信息与资格核对", hours = 1.0, rank = TaskRank.MUST),
                    StageTaskBlueprint("赛题类型与队伍分工确认", hours = 2.0),
                ),
            ),
            StageBlueprint(
                kind = StageKind.PREP,
                name = "赛前准备",
                days = 14,
                deliverables = listOf("分工与协作约定", "往届论文拆解笔记", "工具与环境清单"),
                checkpoints = listOf("每人熟悉一个角色", "往届优秀论文各读一篇"),
                moments = listOf(StageMoment("分工与论文拆解定稿", offsetDays = 13)),
                tasks = listOf(
                    StageTaskBlueprint("往届优秀论文拆解", hours = 6.0),
                    StageTaskBlueprint("建模/编程/写作分工", hours = 2.0),
                    StageTaskBlueprint("工具与环境准备", hours = 3.0),
                    StageTaskBlueprint("数据源与文献准备", hours = 4.0),
                ),
            ),
            StageBlueprint(
                kind = StageKind.SOLVE,
                name = "集中解题",
                days = 3,
                deliverables = listOf("选题结论", "模型与假设", "求解代码与结果", "论文初稿"),
                checkpoints = listOf("第一天中午前定题", "第二天模型与结果对齐", "第三天上午初稿齐"),
                moments = listOf(
                    StageMoment("定题截止", offsetDays = 0),
                    StageMoment("模型与结果对齐", offsetDays = 1),
                    StageMoment("初稿齐", offsetDays = 2, hard = true),
                ),
                tasks = listOf(
                    StageTaskBlueprint("赛题通读与选题", hours = 2.0, rank = TaskRank.MUST),
                    StageTaskBlueprint("模型假设与推导", hours = 10.0, dependsOn = listOf(0)),
                    StageTaskBlueprint("编程求解与结果校验", hours = 10.0, dependsOn = listOf(0)),
                    StageTaskBlueprint("论文初稿", hours = 12.0, dependsOn = listOf(1, 2)),
                    StageTaskBlueprint("摘要与创新点打磨", hours = 3.0, dependsOn = listOf(3)),
                ),
            ),
            StageBlueprint(
                kind = StageKind.WRITE,
                name = "成文提交",
                days = 1,
                deliverables = listOf("论文终稿 PDF", "支撑材料", "查重报告"),
                checkpoints = listOf("格式与页数核对", "提交入口开放即传"),
                moments = listOf(StageMoment("提交截止", offsetDays = 0, hard = true)),
                isGate = true,
                tasks = listOf(
                    StageTaskBlueprint("论文定稿与查重", hours = 4.0, rank = TaskRank.MUST),
                    StageTaskBlueprint("提交与回执确认", hours = 2.0, rank = TaskRank.MUST, dependsOnPreviousStage = true),
                ),
            ),
            StageBlueprint(
                kind = StageKind.DEFENSE,
                name = "答辩与评审（如有）",
                days = 3,
                deliverables = listOf("答辩稿", "评委问答准备"),
                checkpoints = listOf("至少完成一次模拟答辩"),
                moments = listOf(StageMoment("答辩日", offsetDays = 2, hard = true)),
                isGate = true,
                tasks = listOf(
                    StageTaskBlueprint("答辩稿与问答准备", hours = 4.0, rank = TaskRank.MUST, dependsOnPreviousStage = true),
                    StageTaskBlueprint("模拟答辩", hours = 2.0, dependsOn = listOf(0)),
                ),
            ),
        ),
    )

    /** 创新创业类（互联网+/挑战杯）：报名 → 调研立项 → 产品与材料 → 校内评审 → 决赛答辩。 */
    val innovation = StageTemplate(
        id = "innovation",
        label = "创新创业类（互联网+/挑战杯）",
        stages = listOf(
            StageBlueprint(
                kind = StageKind.SIGNUP,
                name = "报名组队",
                days = 10,
                deliverables = listOf("报名信息", "指导教师确认"),
                checkpoints = listOf("报名截止日", "确认参赛赛道与组别"),
                moments = listOf(StageMoment("报名截止", offsetDays = 9, hard = true)),
                tasks = listOf(
                    StageTaskBlueprint("报名与资格核对", hours = 1.0, rank = TaskRank.MUST),
                    StageTaskBlueprint("团队分工与指导教师确认", hours = 2.0),
                ),
            ),
            StageBlueprint(
                kind = StageKind.PREP,
                name = "立项与调研",
                days = 14,
                deliverables = listOf("市场调研报告", "用户访谈纪要", "商业模式画布"),
                checkpoints = listOf("项目方向定稿"),
                tasks = listOf(
                    StageTaskBlueprint("市场调研", hours = 8.0),
                    StageTaskBlueprint("用户访谈与需求整理", hours = 6.0),
                    StageTaskBlueprint("竞品与商业模式画布", hours = 4.0, dependsOn = listOf(0)),
                ),
            ),
            StageBlueprint(
                kind = StageKind.BUILD,
                name = "产品与材料",
                days = 21,
                deliverables = listOf("可演示原型", "项目书初稿", "演示视频素材"),
                checkpoints = listOf("原型可完整走通主流程", "项目书初稿齐"),
                tasks = listOf(
                    StageTaskBlueprint("原型开发", hours = 24.0),
                    StageTaskBlueprint("项目书初稿", hours = 12.0),
                    StageTaskBlueprint("演示视频与截图", hours = 4.0, dependsOn = listOf(0)),
                ),
            ),
            StageBlueprint(
                kind = StageKind.REVIEW,
                name = "校内评审与打磨",
                days = 7,
                deliverables = listOf("打磨后的项目书", "路演PPT"),
                checkpoints = listOf("评审意见逐条回应"),
                moments = listOf(StageMoment("校内评审", offsetDays = 3, hard = true)),
                isGate = true,
                tasks = listOf(
                    StageTaskBlueprint("评审反馈整理", hours = 3.0, rank = TaskRank.MUST),
                    StageTaskBlueprint("项目书打磨", hours = 6.0, dependsOn = listOf(0)),
                    StageTaskBlueprint("路演PPT", hours = 8.0),
                ),
            ),
            StageBlueprint(
                kind = StageKind.DEFENSE,
                name = "校决赛答辩",
                days = 3,
                deliverables = listOf("答辩稿", "模拟答辩记录"),
                checkpoints = listOf("至少完成一次模拟答辩"),
                moments = listOf(StageMoment("决赛答辩", offsetDays = 2, hard = true)),
                isGate = true,
                tasks = listOf(
                    StageTaskBlueprint("答辩稿与问答准备", hours = 4.0, rank = TaskRank.MUST, dependsOnPreviousStage = true),
                    StageTaskBlueprint("模拟答辩", hours = 2.0, dependsOn = listOf(0)),
                ),
            ),
            StageBlueprint(
                kind = StageKind.ARCHIVE,
                name = "复盘归档",
                days = 7,
                deliverables = listOf("复盘笔记", "可复用材料"),
                tasks = listOf(
                    StageTaskBlueprint("复盘与材料归档", hours = 2.0, rank = TaskRank.COULD),
                ),
            ),
        ),
    )

    /** 通用三阶段：拿不准赛事流程时用这个，逐项改比空着强。 */
    val generic = StageTemplate(
        id = "generic",
        label = "通用三阶段",
        stages = listOf(
            StageBlueprint(
                kind = StageKind.PREP,
                name = "前期",
                days = 7,
                deliverables = listOf("方向与资料准备"),
                tasks = listOf(StageTaskBlueprint("资料与方向准备", hours = 4.0)),
            ),
            StageBlueprint(
                kind = StageKind.BUILD,
                name = "中期",
                days = 21,
                deliverables = listOf("核心产出"),
                tasks = listOf(StageTaskBlueprint("核心产出", hours = 16.0, rank = TaskRank.MUST)),
            ),
            StageBlueprint(
                kind = StageKind.WRITE,
                name = "后期",
                days = 7,
                deliverables = listOf("提交材料"),
                checkpoints = listOf("提交前核对格式"),
                moments = listOf(StageMoment("提交截止", offsetDays = 6, hard = true)),
                isGate = true,
                tasks = listOf(StageTaskBlueprint("材料整理与提交", hours = 4.0, rank = TaskRank.MUST)),
            ),
        ),
    )

    val all = listOf(mathModeling, innovation, generic)

    fun byId(id: String): StageTemplate? = all.firstOrNull { it.id == id }

    /** 模板 → 连续阶段窗口：上一环节结束的次日就是下一环节开始，不留空档也不重叠。 */
    fun instantiate(template: StageTemplate, start: LocalDate, phaseIdPrefix: String = "phase"): List<Phase> {
        var cursor = start
        return template.stages.mapIndexed { index, stage ->
            val end = cursor.plusDays((stage.days - 1).toLong())
            Phase(
                phaseId = "$phaseIdPrefix-$index-${stage.kind.name.lowercase()}",
                name = stage.name,
                start = cursor,
                end = end,
                kind = stage.kind,
                deliverables = stage.deliverables,
                checkpoints = stage.checkpoints,
                moments = stage.moments,
                isGate = stage.isGate,
            ).also { cursor = end.plusDays(1) }
        }
    }

    /** 模板 → 基线任务；依赖只落在阶段内，跨环节靠阶段窗口推进，避免一条链全被上游卡死。 */
    fun baselineTasks(
        template: StageTemplate,
        phases: List<Phase>,
        taskIdPrefix: String = "task",
        createdBy: String = "",
    ): List<TeamTask> {
        require(phases.size == template.stages.size) { "阶段数量与模板不一致" }
        return buildList {
            template.stages.forEachIndexed { stageIndex, stage ->
                stage.tasks.forEachIndexed { taskIndex, blueprint ->
                    val dependsOn = buildList {
                        blueprint.dependsOn.forEach { add("$taskIdPrefix-$stageIndex-$it") }
                        if (blueprint.dependsOnPreviousStage && stageIndex > 0) {
                            template.stages[stageIndex - 1].tasks.indices.forEach {
                                add("$taskIdPrefix-${stageIndex - 1}-$it")
                            }
                        }
                    }
                    add(
                        TeamTask(
                            taskId = "$taskIdPrefix-$stageIndex-$taskIndex",
                            title = blueprint.title,
                            tier = blueprint.tier,
                            phaseId = phases[stageIndex].phaseId,
                            estimatedHours = blueprint.hours,
                            rank = blueprint.rank,
                            dependsOn = dependsOn,
                            createdBy = createdBy,
                        ),
                    )
                }
            }
        }
    }

    /** 阶段上的关键时点 → 带日期的清单；硬时点（报名/定题/提交/答辩）不可滑。 */
    fun moments(phases: List<Phase>): List<StageMomentAt> = phases
        .flatMap { phase ->
            phase.moments.map { moment ->
                StageMomentAt(
                    phaseId = phase.phaseId,
                    phaseName = phase.name,
                    date = phase.momentDate(moment),
                    label = moment.label,
                    hard = moment.hard,
                )
            }
        }
        .sortedWith(compareBy({ it.date }, { if (it.hard) 0 else 1 }, { it.label }))

    fun build(template: StageTemplate, start: LocalDate, createdBy: String = ""): StagePlan {
        val phases = instantiate(template, start)
        return StagePlan(
            template = template,
            phases = phases,
            tasks = baselineTasks(template, phases, createdBy = createdBy),
            moments = moments(phases),
        )
    }

    /** 模板总天数；和比赛截止对比就能知道这套环节塞不塞得下。 */
    fun totalDays(template: StageTemplate): Int = template.stages.sumOf { it.days }
}
