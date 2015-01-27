/**
 * 
 */
package org.jocean.xharbor.route;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import org.apache.curator.framework.CuratorFramework;
import org.jocean.event.api.EventReceiverSource;
import org.jocean.idiom.ExceptionUtils;
import org.jocean.idiom.Function;
import org.jocean.idiom.Pair;
import org.jocean.idiom.SimpleCache;
import org.jocean.j2se.MBeanRegisterSupport;
import org.jocean.xharbor.api.Router;
import org.jocean.xharbor.api.RoutingInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.annotation.JSONField;

/**
 * @author isdom
 *
 */
public class RouteUtils {
    private static final Logger LOG = LoggerFactory
            .getLogger(RouteUtils.class);

    @SuppressWarnings("rawtypes")
    public static <I, O> Router<I, O> compositeRouter(
            final Class<O> clsO,
            final Router<I,?> inRouter, 
            final Router ... routers) {
        return new Router<I, O>() {

            @SuppressWarnings("unchecked")
            @Override
            public O calculateRoute(final I input, final Context routectx) {
                Object io = inRouter.calculateRoute(input, routectx);
                for ( Router router : routers ) {
                    io = router.calculateRoute(io, routectx);
                }
                return (O)io;
            }};
    }
    
    public interface RoutesMXBean {
        public String[] getRoutes();
    }
    
    //  TODO replace this to RoutesMXBean
    public interface RouteMXBean {
        public String getRoutes();
    }
    
    public static <INPUT, OUTPUT> CachedRouter<INPUT, OUTPUT> buildCachedRouter(
            final String prefix, 
            final EventReceiverSource source, 
            final Function<Pair<INPUT,OUTPUT>, String> genobjname) {
        final MBeanRegisterSupport routerMbeanSupport = 
                new MBeanRegisterSupport(prefix, null);

        final MBeanRegisterSupport urisMBeanSupport =
                new MBeanRegisterSupport(prefix, null);
      
        return new CachedRouter<INPUT, OUTPUT>(source, 
                new CachedRouter.CacheVisitor<INPUT, OUTPUT>() {
                    @Override
                    public void visit(final SimpleCache<INPUT, OUTPUT> cache)
                            throws Exception {
                        routerMbeanSupport.registerMBean("name=routes", new RoutesMXBean() {
                      @Override
                      public String[] getRoutes() {
                          return new ArrayList<String>() {
                              private static final long serialVersionUID = 1L;
                          {
                              final Iterator<Map.Entry<INPUT, OUTPUT>> itr = cache.snapshot().entrySet().iterator();
                              while (itr.hasNext()) {
                                  final Map.Entry<INPUT, OUTPUT> entry = itr.next();
                                  this.add(entry.getKey() + "-->" + entry.getValue().toString());
                              }
                          }}.toArray(new String[0]);
                      }});
                    }
                },
                new CachedRouter.OnRouterUpdated<INPUT, OUTPUT>() {
                    @Override
                    public void visit(final Router<INPUT, OUTPUT> prevImpl, final Router<INPUT, OUTPUT> newImpl)
                            throws Exception {
                      if ( null != prevImpl ) {
                          routerMbeanSupport.unregisterMBean("name=rules");
                      }
                      routerMbeanSupport.registerMBean("name=rules", newImpl);
                      urisMBeanSupport.unregisterAllMBeans();
                    }}, 
                new CachedRouter.OnRouted<INPUT, OUTPUT>() {
                    @Override
                    public void visit(final INPUT input, final OUTPUT output) throws Exception {
                        final String objname = genobjname.apply(Pair.of(input, output));
                        if (null != objname && !urisMBeanSupport.isRegistered(objname)) {
                            urisMBeanSupport.registerMBean(objname,
                                    new RouteMXBean() {
                                        @Override
                                        public String getRoutes() {
                                            return output.toString();
                                        }
                                    });
                        }
                    }});
    }
    
    public static Router<RoutingInfo, ?> buildRoutingInfoRouterFromZK(
            final CuratorFramework client, final String path) 
            throws Exception {
        final RoutingInfo2Dispatcher router = new RoutingInfo2Dispatcher();
        final List<String> levels = client.getChildren().forPath(path);
        for ( String priority : levels ) {
            try {
                addRules(client, router, path + "/" + priority, Integer.parseInt(priority));
            }
            catch (NumberFormatException e) {
                LOG.warn("invalid priority for can't convert to integer, detail: {}", 
                        ExceptionUtils.exception2detail(e));
            }
        }
        return router;
    }

    private static void addRules(
            final CuratorFramework client, 
            final RoutingInfo2Dispatcher router,
            final String pathToLevel,
            final int priority) throws Exception {
        final List<String> hosts = client.getChildren().forPath(pathToLevel);
        for ( String host : hosts ) {
            final Pair<String, RoutingInfo[]> rule = 
                    generateRule(client, pathToLevel + "/" + host, host);
            if ( null != rule ) {
                try {
                    router.addOrUpdateRule(priority, rule.getFirst(), rule.getSecond());
                } catch (Exception e) {
                    LOG.warn("exception when add rule({}/{}) for level({}), detail:{}",
                            rule.getFirst(), Arrays.toString(rule.getSecond()), 
                            pathToLevel, ExceptionUtils.exception2detail(e));
                }
            }
        }
    }

    public static class RuleDesc {
        
        public static class InfoDesc implements RoutingInfo {

            public String method;
            public String path;
            
            @Override
            public String getMethod() {
                return method;
            }

            @Override
            public String getPath() {
                return path;
            }
        }
        
        private String descrption;
        private String scheme;
        private InfoDesc[] regexs;
        
        @JSONField(name="descrption")
        public String getDescrption() {
            return descrption;
        }

        @JSONField(name="descrption")
        public void setDescrption(String descrption) {
            this.descrption = descrption;
        }

        @JSONField(name="scheme")
        public String getScheme() {
            return scheme;
        }
        
        @JSONField(name="scheme")
        public void setScheme(String scheme) {
            this.scheme = scheme;
        }
        
        @JSONField(name="regexs")
        public InfoDesc[] getRegexs() {
            return regexs;
        }
        
        @JSONField(name="regexs")
        public void setRegexs(InfoDesc[] regexs) {
            this.regexs = regexs;
        }
    }
    
    private static Pair<String, RoutingInfo[]> generateRule(
            final CuratorFramework client, 
            final String pathToHost, 
            final String host)
            throws Exception {
        final byte[] content = client.getData().forPath(pathToHost);
        if ( content != null && content.length > 0) {
            final RuleDesc desc = JSON.parseObject(new String(content,  "UTF-8"), RuleDesc.class);
            if ( null != desc ) {
                LOG.debug("generateRule for {}/{}", desc.getScheme(), Arrays.toString( desc.getRegexs() ) );
                return Pair.of(desc.getScheme() + "://" + host,
                        Arrays.asList( desc.getRegexs() ).toArray(new RoutingInfo[0]));
            }
        }
        return null;
    }
}
