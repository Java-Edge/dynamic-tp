package com.dtp.core.notify.alarm;

import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.NumberUtil;
import com.dtp.common.ApplicationContextHolder;
import com.dtp.common.dto.AlarmInfo;
import com.dtp.common.dto.ExecutorWrapper;
import com.dtp.common.dto.NotifyItem;
import com.dtp.common.em.NotifyTypeEnum;
import com.dtp.common.em.RejectedTypeEnum;
import com.dtp.common.pattern.filter.Filter;
import com.dtp.common.pattern.filter.FilterChain;
import com.dtp.common.pattern.filter.FilterChainFactory;
import com.dtp.core.context.AlarmCtx;
import com.dtp.core.context.BaseNotifyCtx;
import com.dtp.core.notify.NotifyHelper;
import com.dtp.core.notify.filter.AlarmBaseFilter;
import com.dtp.core.notify.filter.NotifyFilter;
import com.dtp.core.notify.invoker.AlarmInvoker;
import com.dtp.core.support.ThreadPoolBuilder;
import com.dtp.core.thread.DtpExecutor;
import com.google.common.collect.Lists;
import lombok.extern.slf4j.Slf4j;
import lombok.val;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.stream.Collectors;

import static com.dtp.common.em.QueueTypeEnum.LINKED_BLOCKING_QUEUE;

/**
 * AlarmManager related
 *
 * @author: yanhom
 * @since 1.0.0
 */
@Slf4j
public class AlarmManager {

    private static final ExecutorService ALARM_EXECUTOR = ThreadPoolBuilder.newBuilder()
            .threadPoolName("dtp-alarm")
            .threadFactory("dtp-alarm")
            .corePoolSize(2)
            .maximumPoolSize(4)
            .workQueue(LINKED_BLOCKING_QUEUE.getName(), 2000, false, null)
            .rejectedExecutionHandler(RejectedTypeEnum.DISCARD_OLDEST_POLICY.getName())
            .dynamic(false)
            .buildWithTtl();

    private static final FilterChain<BaseNotifyCtx> ALARM_FILTER_CHAIN;

    static {
        val filters = ApplicationContextHolder.getBeansOfType(NotifyFilter.class);
        Collection<NotifyFilter> alarmFilters = Lists.newArrayList(filters.values());
        alarmFilters.add(new AlarmBaseFilter());
        alarmFilters = alarmFilters.stream()
                .sorted(Comparator.comparing(Filter::getOrder))
                .collect(Collectors.toList());
        ALARM_FILTER_CHAIN = FilterChainFactory.buildFilterChain(new AlarmInvoker(),
                alarmFilters.toArray(new NotifyFilter[0]));
    }

    private AlarmManager() {}

    public static void triggerAlarm(String dtpName, String notifyType, Runnable runnable) {
        AlarmCounter.incAlarmCounter(dtpName, notifyType);
        ALARM_EXECUTOR.execute(runnable);
    }

    public static void triggerAlarm(Runnable runnable) {
        ALARM_EXECUTOR.execute(runnable);
    }

    public static void doAlarm(DtpExecutor executor, List<NotifyTypeEnum> notifyTypes) {
        val executorWrapper = new ExecutorWrapper(executor.getThreadPoolName(), executor, executor.getNotifyItems());
        doAlarm(executorWrapper, notifyTypes);
    }

    public static void doAlarm(ExecutorWrapper executorWrapper, List<NotifyTypeEnum> notifyTypes) {
        notifyTypes.forEach(x -> doAlarm(executorWrapper, x));
    }

    public static void doAlarm(DtpExecutor executor, NotifyTypeEnum notifyType) {
        val executorWrapper = new ExecutorWrapper(executor.getThreadPoolName(), executor, executor.getNotifyItems());
        doAlarm(executorWrapper, notifyType);
    }

    public static void doAlarm(ExecutorWrapper executorWrapper, NotifyTypeEnum notifyType) {
        NotifyItem notifyItem = NotifyHelper.getNotifyItem(executorWrapper, notifyType);
        if (notifyItem == null) {
            return;
        }
        AlarmCtx alarmCtx = new AlarmCtx(executorWrapper, notifyItem, notifyType);
        ALARM_FILTER_CHAIN.fire(alarmCtx);
    }

    public static boolean checkThreshold(ExecutorWrapper executor, NotifyTypeEnum notifyType, NotifyItem notifyItem) {

        switch (notifyType) {
            case CAPACITY:
                return checkCapacity(executor, notifyItem);
            case LIVENESS:
                return checkLiveness(executor, notifyItem);
            case REJECT:
            case RUN_TIMEOUT:
            case QUEUE_TIMEOUT:
                return checkWithAlarmInfo(executor, notifyItem);
            default:
                log.error("Unsupported alarm type, type: {}", notifyType);
                return false;
        }
    }

    public static boolean satisfyBaseCondition(NotifyItem notifyItem) {
        return notifyItem.isEnabled() && CollUtil.isNotEmpty(notifyItem.getPlatforms());
    }

    private static boolean checkLiveness(ExecutorWrapper executorWrapper, NotifyItem notifyItem) {
        val executor = (ThreadPoolExecutor) executorWrapper.getExecutor();
        int maximumPoolSize = executor.getMaximumPoolSize();
        double div = NumberUtil.div(executor.getActiveCount(), maximumPoolSize, 2) * 100;
        return div >= notifyItem.getThreshold();
    }

    private static boolean checkCapacity(ExecutorWrapper executorWrapper, NotifyItem notifyItem) {

        val executor = (ThreadPoolExecutor) executorWrapper.getExecutor();
        BlockingQueue<Runnable> workQueue = executor.getQueue();
        if (CollUtil.isEmpty(workQueue)) {
            return false;
        }

        int queueCapacity = executor.getQueue().size() + executor.getQueue().remainingCapacity();
        double div = NumberUtil.div(workQueue.size(), queueCapacity, 2) * 100;
        return div >= notifyItem.getThreshold();
    }

    private static boolean checkWithAlarmInfo(ExecutorWrapper executorWrapper, NotifyItem notifyItem) {
        AlarmInfo alarmInfo = AlarmCounter.getAlarmInfo(executorWrapper.getThreadPoolName(), notifyItem.getType());
        return alarmInfo.getCount() >= notifyItem.getThreshold();
    }
}
