package com.share.order.receiver;

import com.rabbitmq.client.AMQP;
import com.share.common.rabbit.constant.MqConst;
import com.share.order.config.DeadLetterMqConfig;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.Exchange;
import org.springframework.amqp.rabbit.annotation.Queue;
import org.springframework.amqp.rabbit.annotation.QueueBinding;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class TestReceiver {
    /**
     * 监听消息
     * @param message
     */
    @SneakyThrows
    @RabbitListener(bindings = @QueueBinding(
            exchange = @Exchange(value = MqConst.EXCHANGE_TEST, durable = "true"),
            value = @Queue(value = MqConst.QUEUE_TEST, durable = "true"),
            key = MqConst.ROUTING_TEST
    ))
    public void test(String content, Message message) {
        //都可以
        log.info("接收消息：{}", content);
        log.info("接收消息：{}", new String(message.getBody()));
    }



    @RabbitListener(bindings = @QueueBinding(
            value = @Queue(name = "delay.queue", durable = "true"),
            exchange = @Exchange(name = "delay.direct", delayed = "true"),
            key = "delay"
    ))
    public void listenDelayMessage(String msg){
        log.info("接收到delay.queue的延迟消息：{}", msg);
    }


    /**
     * 监听延迟消息
     * @param msg
     * @param message
     * @param channel
     */
//    @SneakyThrows
//    @RabbitListener(queues = {DeadLetterMqConfig.queue_dead_2})
//    public void getDeadLetterMsg(String msg, Message message, AMQP.Channel channel) {
//        log.info("死信消费者：{}", msg);
//        channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
//    }
}