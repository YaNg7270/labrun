package labrun.android.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import labrun.android.Repository
import labrun.android.Storage
import labrun.core.AnchorTrigger
import labrun.core.Severity
import labrun.core.TimeFormat

@Composable
fun PreviewScreen(repo: Repository, bytes: ByteArray, fromLibrary: Boolean, modifier: Modifier, onBack: () -> Unit, onStarted: () -> Unit) {
    val c = LocalLab.current
    val result = remember(bytes) { repo.parse(bytes) }
    val p = result.protocol
    Column(modifier.verticalScroll(rememberScrollState()).padding(horizontal = 16.dp)) {
        TextButton(onClick = onBack, modifier = Modifier.padding(top = 8.dp)) { Text("‹ 返回") }
        Text(p?.title ?: "步骤文件有错误", style = LabType.title, modifier = Modifier.padding(bottom = 4.dp))
        if (p != null) Text("${p.protocolId} · 版本 ${p.protocolVersion} · SHA-256 ${result.sha256.take(12)}", style = LabType.caption, color = c.muted)

        if (result.issues.isNotEmpty()) {
            SectionTitle("校验结果：${result.errors.size} 个错误，${result.warnings.size} 个提醒")
            result.issues.forEach { i ->
                val col = if (i.severity == Severity.ERROR) c.overdue else c.due
                Column(Modifier.padding(bottom = 6.dp)) {
                    Text("${if (i.severity == Severity.ERROR) "错误" else "提醒"} · ${i.message}", style = LabType.body, color = col)
                    Text("${i.code} @ ${i.path}", style = LabType.caption, color = c.muted)
                }
            }
        }
        if (p == null) { Spacer(Modifier.height(24.dp)); return@Column }

        p.description?.let { Text(it, style = LabType.body, color = c.muted, modifier = Modifier.padding(top = 8.dp)) }

        SectionTitle("计时起点（${p.anchors.size}）")
        p.anchors.forEach { a ->
            val how = when (val t = a.createOn) {
                AnchorTrigger.RunStarted -> "点击“开始实验”时"
                is AnchorTrigger.StepConfirmed -> "确认“${p.step(t.stepId).title}”时"
                AnchorTrigger.Manual -> "运行中手动建立"
            }
            Text("${a.label}：$how", style = LabType.body)
        }

        SectionTitle("步骤（${p.steps.size}）")
        p.steps.forEachIndexed { i, s ->
            Row(Modifier.padding(bottom = 8.dp)) {
                Text("${i + 1}", style = LabType.title, color = c.muted, modifier = Modifier.width(28.dp))
                Column {
                    Text(s.title, style = LabType.body)
                    s.instruction?.let { Text(it, style = LabType.caption, color = c.muted) }
                    s.notBefore?.let { Text("计划：${p.anchor(it.anchorId).label} ${TimeFormat.elapsed(it.offsetSeconds * 1000)} 之后", style = LabType.caption, color = c.due) }
                    s.samplingPlanIds.forEach { id -> Text("期间采样：${p.plan(id).label}", style = LabType.caption, color = c.primary) }
                }
            }
        }

        if (p.samplingPlans.isNotEmpty()) {
            SectionTitle("采样计划")
            p.samplingPlans.forEach { pl ->
                Text(pl.label, style = LabType.body)
                Text("以“${p.anchor(pl.anchorId).label}”为起点：" + pl.offsetsSeconds.joinToString("、") { TimeFormat.elapsed(it * 1000) }, style = LabType.caption, color = c.muted)
                Text("记录：" + pl.fields.joinToString("、") { f -> f.label + (f.unit?.let { "（$it）" } ?: "") }, style = LabType.caption, color = c.muted,
                    modifier = Modifier.padding(bottom = 8.dp))
            }
        }

        Spacer(Modifier.height(16.dp))
        if (!fromLibrary) OutlinedButton(onClick = {
            when (val r = repo.saveProtocol(bytes)) {
                is Storage.SaveResult.Saved -> repo.message = "已保存到步骤文件库"
                is Storage.SaveResult.AlreadyThere -> repo.message = "步骤文件库里已有相同文件"
                is Storage.SaveResult.Conflict -> repo.message = r.message
            }
        }, modifier = Modifier.fillMaxWidth().height(48.dp)) { Text("保存到步骤文件库") }
        Spacer(Modifier.height(8.dp))
        Button(onClick = {
            if (!fromLibrary) {
                val r = repo.saveProtocol(bytes)
                if (r is Storage.SaveResult.Conflict) { repo.message = r.message; return@Button }
            }
            if (repo.startRun(bytes)) onStarted()
        }, enabled = result.ok && repo.state == null, modifier = Modifier.fillMaxWidth().height(52.dp)) {
            Text(if (repo.state != null) "已有进行中的实验" else "开始实验（建立全局起点）")
        }
        if (fromLibrary) {
            var confirmDelete by remember { mutableStateOf(false) }
            TextButton(onClick = { if (!confirmDelete) confirmDelete = true else { repo.deleteProtocol(bytes); onBack() } },
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
                Text(if (confirmDelete) "再点一次确认删除（已做过的实验不受影响）" else "从步骤文件库删除", color = c.overdue)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}
