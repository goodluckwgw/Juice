package com.hujiang.juice.rest.service;

import com.google.common.collect.Lists;
import com.google.common.collect.Maps;
import com.google.gson.Gson;
import com.hujiang.jooq.juice.tables.pojos.JuiceTask;
import com.hujiang.juice.common.error.CommonStatusCode;
import com.hujiang.juice.common.error.ErrorCode;
import com.hujiang.juice.common.exception.RestException;
import com.hujiang.juice.common.model.TaskManagement;
import com.hujiang.juice.common.utils.cache.CacheUtils;
import com.hujiang.juice.common.utils.generator.IdGenerator;
import com.hujiang.juice.common.vo.SubmitTask;
import com.hujiang.juice.common.vo.TaskKill;
import com.hujiang.juice.common.vo.TaskResult;

import com.hujiang.juice.common.vo.TaskReconcile;
import com.hujiang.juice.common.utils.db.DaoUtils;
import com.hujiang.juice.rest.config.CachesBizConfig;
import com.hujiang.juice.rest.utils.TaskUtils;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;

import static com.hujiang.juice.common.config.COMMON.KILL;
import static com.hujiang.juice.common.config.COMMON.RECONCILE;

/**
 * Created by xujia on 16/12/5.
 */

@Slf4j
@Data
@Service
public class RestService {

    @Autowired
    private DaoUtils daoUtils;

    @Autowired
    private CacheUtils cacheUtils;

    @Autowired
    private CachesBizConfig cachesBizConfig;

    private Gson gson = new Gson();

    private static final String POINT = ".";

    public long submits(SubmitTask object, String tenantId) {

        //  set run mode
        object.setRunMode(TaskUtils.checkRunMode(object));

        //  generate taskId
        object.setTaskId(IdGenerator.nextId());
        log.info("TaskId --> " + object.getTaskId() + ", TaskName : " + object.getTaskName());

        //  submit(record in db and submit in redis)
        daoUtils.getContext().transaction(
                configuration -> {
                    String commands = "";
                    String dockerImage = "";
                    if (object.getRunMode() == SubmitTask.RunModel.CONTAINER) {
                        dockerImage = object.getContainer().getDocker().getImage();
                    } else {
                        commands = object.getCommands();
                    }
                    boolean isInsert = daoUtils.submit(configuration, object.getTaskId(), tenantId, object.getCallbackUrl(), object.getTaskName(), dockerImage, commands);
                    if (isInsert) {
                        cacheUtils.pushToQueue(cachesBizConfig.getTaskQueue(), gson.toJson(object.toTask()));
                    }
                }
        );

        return object.getTaskId();
    }

    public TaskKill kills(String tenantId, long taskId) {
        JuiceTask task = daoUtils.queryTask(tenantId, taskId);

        if (null == task) {
            throw new RestException(CommonStatusCode.QUERY_RECORD_EMPTY.getStatus(), "task not exist to kill!");
        }

        if (task.getTaskStatus() > TaskResult.Result.RUNNING.getType()) {
            return new TaskKill(false, task.getTaskStatus(), task.getMessage());
        }

        TaskManagement taskManagement = new TaskManagement(Lists.newCopyOnWriteArrayList(), KILL);
        TaskManagement.TaskAgentRel taskAgentRel = new TaskManagement.TaskAgentRel(task.getTaskId(), task.getTaskName(), task.getAgentId());
        taskManagement.getTaskAgentRels().add(taskAgentRel);
//
//        String key = cachesBizConfig.getKillKeyPrefix() + POINT + taskId;
//        cacheUtils.setExpired(key, "1", cachesBizConfig.getExpiredSeconds());
        cacheUtils.pushToQueue(cachesBizConfig.getManagementQueue(), gson.toJson(taskManagement));
        return new TaskKill(true, task.getTaskStatus(), "juice accept kill task command");

    }

    public List<JuiceTask> querys(String tenantId, List<Long> taskId) {
        return daoUtils.queryTasks(tenantId, taskId);
    }

    public TaskReconcile reconciles(String tenantId, List<Long> taskIds) {
        List<JuiceTask> tasks = daoUtils.queryTasks(tenantId, taskIds);
        Map<Long, TaskReconcile.Reconcile> reconcileMap = getTaskReconcile(taskIds);
        TaskManagement taskManagement = new TaskManagement(Lists.newCopyOnWriteArrayList(), RECONCILE);
        tasks.stream().parallel().forEach(t -> {
            String value = t.getAgentId();

            boolean isReconciled = false;
            String message = "";
            TaskReconcile.Reconcile reconcile = reconcileMap.get(t.getTaskId());
            if (null == reconcile) {
                String error = "taskId not matched with database record, taskId: " + t.getTaskId();
                log.warn(error);
                throw new RestException(ErrorCode.OBJECT_NOT_EQUAL_ERROR.getCode(), error);
            } else if (!t.getTaskStatus().equals(TaskResult.Result.RUNNING.getType())) {
                message = "not reconcile due to terminal task status : " + TaskResult.Result.getName(t.getTaskStatus());
            } else if (StringUtils.isBlank(value)) {
                reconcile.setReconciled(false);
                daoUtils.finishTask(t.getTaskId(), TaskResult.Result.EXPIRED.getType(), "task expired");
                message = "not reconcile due to terminal task status : " + TaskResult.Result.EXPIRED.name();
            } else {
                TaskManagement.TaskAgentRel taskAgentRel = new TaskManagement.TaskAgentRel(t.getTaskId(), t.getTaskName(), value);
                taskManagement.getTaskAgentRels().add(taskAgentRel);
                isReconciled = true;
                message = "reconcile task";
            }

            reconcile.setTaskId(t.getTaskId());
            reconcile.setReconciled(isReconciled);
            reconcile.setMessage(message);

        });
        int reconcileCount = taskManagement.getTaskAgentRels().size();
        if (reconcileCount > 0) {
            cacheUtils.pushToQueue(cachesBizConfig.getManagementQueue(), gson.toJson(taskManagement));
        }
        return new TaskReconcile(taskIds.size(), reconcileCount, mapsToLists(reconcileMap));
    }

    private Map<Long, TaskReconcile.Reconcile> getTaskReconcile(List<Long> tasks) {
        Map<Long, TaskReconcile.Reconcile> reconcileMap = Maps.newConcurrentMap();
        tasks.stream().parallel().forEach(v -> {
            reconcileMap.put(v, new TaskReconcile.Reconcile(v, false, "invalid taskId"));
        });
        return reconcileMap;
    }

    private List<TaskReconcile.Reconcile> mapsToLists(Map<Long, TaskReconcile.Reconcile> map) {
        final List<TaskReconcile.Reconcile> reconciles = Lists.newCopyOnWriteArrayList();
        map.entrySet().parallelStream().forEach(
                v -> {
                    reconciles.add(v.getValue());
                }
        );
        return reconciles;
    }
}
