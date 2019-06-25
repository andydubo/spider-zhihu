package com.netopstec.spiderzhihu.crawler;

import cn.wanghaomiao.seimi.annotation.Crawler;
import cn.wanghaomiao.seimi.def.BaseSeimiCrawler;
import cn.wanghaomiao.seimi.struct.Request;
import cn.wanghaomiao.seimi.struct.Response;
import com.netopstec.spiderzhihu.constant.Constants;
import com.netopstec.spiderzhihu.domain.*;
import com.netopstec.spiderzhihu.json.FollowInfo;
import com.netopstec.spiderzhihu.json.UserInfo;
import com.netopstec.spiderzhihu.service.IpProxyService;
import com.netopstec.spiderzhihu.util.JsonUtil;
import lombok.extern.slf4j.Slf4j;
import org.seimicrawler.xpath.JXDocument;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 爬取关注者信息的爬虫类（关注了$rootName的用户信息）
 * @author zhenye 2019/6/19
 */
@Slf4j
@Crawler(name = "user-follower-crawler", useUnrepeated = false)
public class FollowerCrawler extends BaseSeimiCrawler {

    @Value("${zhihu.root.name}")
    private String rootName;
    @Autowired
    private UserRepository userRepository;
    @Autowired
    private FollowerRelationRepository followerRelationRepository;
    @Autowired
    private IpProxyService ipProxyService;

    @Override
    public String proxy() {
        IpProxy ipProxy = ipProxyService.getActiveProxyIp();
        if (ipProxy != null) {
            log.info("本次用的代理是: [{}:{}]", ipProxy.getIp(), ipProxy.getPort());
            return ipProxy.getType().toLowerCase() + "://" + ipProxy.getIp() + ":" + ipProxy.getPort();
        }
        log.info("由于没有一个可用的代理IP，因此用的是本机IP。注意可能会被加入黑名单。");
        return super.proxy();
    }

    @Override
    public String getUserAgent() {
        return Constants.refreshMyUserAgent();
    }

    private static Integer LIMIT = 20;
    private static Integer OFFSET = 0;

    @Override
    public String[] startUrls() {
        if (rootName == null || "".equals(rootName)) {
            return null;
        }
        log.info("正在爬取关注[{}]的知乎用户信息...", rootName);
        return new String[]{
                "https://www.zhihu.com/api/v4/members/" + rootName + "/followers?limit=" + FollowerCrawler.LIMIT + "&offset=" + FollowerCrawler.OFFSET
        };
    }

    @Override
    public void start(Response response) {
        User followeeUser = userRepository.findUserByUrlToken(rootName);
        if (followeeUser == null) {
            log.error("要预先保存[{}]的用户信息，否则无法保证关联的关注关系", rootName);
            return;
        }
        JXDocument document = response.document();
        String followerListJson = document.selN("body").get(0).asElement().text();
        // 爬取的知乎用户数据中，有些headline字段的值可能有双引号。删除内部的内容防止解析报错
        followerListJson = JsonUtil.removeTheStringFieldValue(followerListJson, false, "headline", "url_token");
        FollowInfo followInfo = JsonUtil.string2Obj(followerListJson, FollowInfo.class);
        Long totals = followInfo.getPaging().getTotals();
        log.info("总共要爬取关注当前用户的知乎用户总数量为：" + totals);
        if (totals == 0) {
            return;
        }
        List<UserInfo> userInfoList = followInfo.getData();
        List<User> userList = new ArrayList<>();
        for (UserInfo userInfo : userInfoList) {
            User user = UserInfo.toEntity(userInfo);
            userList.add(user);
        }
        // 很明显的，每个知乎用户的有唯一的zhihu_user_id和url_token。因此入库之前需要去重（以zhihu_user_id过滤即可）。
        List<String> zhihuUserIdList = userList.stream()
                .map(User::getZhihuUserId)
                .collect(Collectors.toList());
        List<User> duplicateUserList = userRepository.findByZhihuUserIdIn(zhihuUserIdList);
        List<String> duplicateZhihuUserIdList = duplicateUserList.stream()
                .map(User::getZhihuUserId)
                .collect(Collectors.toList());
        List<User> thisTimeToAddUserList = userList.stream()
                .filter(user -> !duplicateZhihuUserIdList.contains(user.getZhihuUserId()))
                .collect(Collectors.toList());
        log.info("本次要保存用户信息的知乎用户总数量为：" + thisTimeToAddUserList.size());
        for (User user : thisTimeToAddUserList) {
            User followerUser = userRepository.save(user);
            FollowerRelation followerRelation = new FollowerRelation();
            followerRelation.setFolloweeId(followeeUser.getId());
            followerRelation.setFollowerId(followerUser.getId());
            followerRelationRepository.save(followerRelation);
        }
        Integer hasGetTotal = FollowerCrawler.OFFSET + FollowerCrawler.LIMIT;
        if (hasGetTotal < totals) {
            log.info("已经爬取的数据条数[{}]，需要爬取的数据条数[{}]，因此还需要爬取下一页的数据", hasGetTotal, totals);
            FollowerCrawler.OFFSET += FollowerCrawler.LIMIT;
            // 这里可以间隔0.5秒后，再爬取下一页的数据（防止IP被封）
            saveNextPageFollowerInfo();
        } else {
            log.info("已经爬取完关注[{}]的所有知乎用户的信息...", rootName);
        }
    }

    /**
     * 爬取下一页的关注者信息
     */
    private void saveNextPageFollowerInfo() {
        String url = "https://www.zhihu.com/api/v4/members/" + rootName + "/followers?limit=" + FollowerCrawler.LIMIT + "&offset=" + FollowerCrawler.OFFSET;
        Request request = Request.build(url, "start");
        push(request);
    }
}
