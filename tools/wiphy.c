/*
 * wiphy - minimal nl80211 probe / AP-interface creator for bcmdhd watches.
 *
 *   wiphy            dump wiphy index, supported iftypes and interface combinations
 *   wiphy addap ap0  create an AP-type virtual interface (auto-detects wiphy index)
 *   wiphy addap ap0 4  ... with explicit wiphy index
 *
 * Why: the vendor HAL and wificond refuse AP interfaces on these watches because
 * the boot-default bcmdhd op_mode lacks the AP bit. After reloading the module
 * with op_mode=15 the wiphy advertises AP, and this tool creates the vif directly
 * over generic netlink (no libnl dependency).
 *
 * Build (static armv7, any Android NDK):
 *   $NDK/toolchains/llvm/prebuilt/windows-x86_64/bin/clang.exe \
 *       --target=armv7a-linux-androideabi21 -static -O2 wiphy.c -o wiphy
 * The prebuilt binary ships in app/src/main/assets/wiphy and is deployed to
 * /data/local/tmp/dgsh/wiphy by the app; rebuild only when changing this file.
 */
#include <stdio.h>
#include <string.h>
#include <stdlib.h>
#include <unistd.h>
#include <errno.h>
#include <sys/socket.h>
#include <linux/netlink.h>
#include <linux/genetlink.h>
#include <linux/nl80211.h>

static int seq = 1;
static unsigned char buf[16384];
static unsigned char pay[4096];

static int put_attr(unsigned char *p, int type, const void *data, int len) {
    struct nlattr *na = (struct nlattr *)p;
    na->nla_len = NLA_HDRLEN + len;
    na->nla_type = type;
    if (len) memcpy(p + NLA_HDRLEN, data, len);
    return NLA_ALIGN(na->nla_len);
}

static int nlsend(int fd, int type, int cmd, int flags, int plen) {
    struct sockaddr_nl sa;
    struct nlmsghdr *nlh = (struct nlmsghdr *)buf;
    struct genlmsghdr *gh;
    memset(&sa, 0, sizeof(sa));
    sa.nl_family = AF_NETLINK;
    memset(buf, 0, sizeof(buf));
    nlh->nlmsg_len = NLMSG_LENGTH(GENL_HDRLEN) + plen;
    nlh->nlmsg_type = type;
    nlh->nlmsg_flags = flags | NLM_F_REQUEST;
    nlh->nlmsg_seq = seq++;
    gh = (struct genlmsghdr *)NLMSG_DATA(nlh);
    gh->cmd = cmd;
    gh->version = 1;
    if (plen) memcpy((unsigned char *)gh + GENL_HDRLEN, pay, plen);
    return sendto(fd, buf, nlh->nlmsg_len, 0, (struct sockaddr *)&sa, sizeof(sa));
}

static int nlrecv(int fd, unsigned char *out, int outlen) {
    struct sockaddr_nl sa;
    socklen_t sl = sizeof(sa);
    int n = recvfrom(fd, out, outlen, 0, (struct sockaddr *)&sa, &sl);
    return n;
}

static void walk_iftypes(struct nlattr *na, int len, const char *tag) {
    int rem = len;
    struct nlattr *n = na;
    printf("%s:", tag);
    while (rem >= (int)sizeof(struct nlattr)) {
        if (n->nla_len < sizeof(struct nlattr) || n->nla_len > rem) break;
        printf(" %d", n->nla_type);
        rem -= NLA_ALIGN(n->nla_len);
        n = (struct nlattr *)((unsigned char *)n + NLA_ALIGN(n->nla_len));
    }
    printf("\n");
}

static void dump_combos(struct nlattr *comb, int len) {
    int rem = len;
    struct nlattr *c = comb;
    while (rem >= (int)sizeof(struct nlattr)) {
        if (c->nla_len < sizeof(struct nlattr) || c->nla_len > rem) break;
        /* each combination: nested limits */
        int rem2 = c->nla_len - NLA_HDRLEN;
        struct nlattr *lim = (struct nlattr *)((unsigned char *)c + NLA_HDRLEN);
        printf("combo:\n");
        while (rem2 >= (int)sizeof(struct nlattr)) {
            if (lim->nla_len < sizeof(struct nlattr) || lim->nla_len > rem2) break;
            if (lim->nla_type == NL80211_IFACE_COMB_LIMITS) {
                int rem3 = lim->nla_len - NLA_HDRLEN;
                struct nlattr *l3 = (struct nlattr *)((unsigned char *)lim + NLA_HDRLEN);
                while (rem3 >= (int)sizeof(struct nlattr)) {
                    if (l3->nla_len < sizeof(struct nlattr) || l3->nla_len > rem3) break;
                    int rem4 = l3->nla_len - NLA_HDRLEN;
                    struct nlattr *l4 = (struct nlattr *)((unsigned char *)l3 + NLA_HDRLEN);
                    unsigned max = 0;
                    while (rem4 >= (int)sizeof(struct nlattr)) {
                        if (l4->nla_len < sizeof(struct nlattr) || l4->nla_len > rem4) break;
                        if (l4->nla_type == NL80211_IFACE_LIMIT_MAX)
                            memcpy(&max, (unsigned char *)l4 + NLA_HDRLEN, 4);
                        if (l4->nla_type == NL80211_IFACE_LIMIT_TYPES)
                            walk_iftypes((struct nlattr *)((unsigned char *)l4 + NLA_HDRLEN),
                                         l4->nla_len - NLA_HDRLEN, "  limit types");
                        rem4 -= NLA_ALIGN(l4->nla_len);
                        l4 = (struct nlattr *)((unsigned char *)l4 + NLA_ALIGN(l4->nla_len));
                    }
                    printf("  max=%u\n", max);
                    rem3 -= NLA_ALIGN(l3->nla_len);
                    l3 = (struct nlattr *)((unsigned char *)l3 + NLA_ALIGN(l3->nla_len));
                }
            }
            rem2 -= NLA_ALIGN(lim->nla_len);
            lim = (struct nlattr *)((unsigned char *)lim + NLA_ALIGN(lim->nla_len));
        }
        rem -= NLA_ALIGN(c->nla_len);
        c = (struct nlattr *)((unsigned char *)c + NLA_ALIGN(c->nla_len));
    }
}

int main(int argc, char **argv) {
    unsigned char rb[16384];
    int fd = socket(AF_NETLINK, SOCK_RAW, NETLINK_GENERIC);
    if (fd < 0) { perror("socket"); return 1; }
    struct sockaddr_nl local;
    memset(&local, 0, sizeof(local));
    local.nl_family = AF_NETLINK;
    bind(fd, (struct sockaddr *)&local, sizeof(local));

    int l = put_attr(pay, CTRL_ATTR_FAMILY_NAME, "nl80211", 8);
    nlsend(fd, GENL_ID_CTRL, CTRL_CMD_GETFAMILY, 0, l);
    int n = nlrecv(fd, rb, sizeof(rb));
    int fam = -1;
    struct nlmsghdr *nlh = (struct nlmsghdr *)rb;
    printf("getfamily reply n=%d type=%d\n", n, n > 0 ? nlh->nlmsg_type : -1);
    if (n > 0 && nlh->nlmsg_type == NLMSG_ERROR) {
        struct nlmsgerr *e = (struct nlmsgerr *)NLMSG_DATA(nlh);
        printf("getfamily err=%d (%s)\n", e->error, strerror(-e->error));
    }
    if (n > 0 && nlh->nlmsg_type != NLMSG_ERROR) {
        struct genlmsghdr *gh = (struct genlmsghdr *)NLMSG_DATA(nlh);
        int rem = nlh->nlmsg_len - NLMSG_LENGTH(GENL_HDRLEN);
        struct nlattr *na = (struct nlattr *)((unsigned char *)gh + GENL_HDRLEN);
        while (rem >= (int)sizeof(struct nlattr)) {
            if (na->nla_len < sizeof(struct nlattr) || na->nla_len > rem) {
                printf("attr break len=%d rem=%d\n", na->nla_len, rem);
                break;
            }
            printf("attr type=%d len=%d\n", na->nla_type, na->nla_len);
            if (na->nla_type == CTRL_ATTR_FAMILY_ID) {
                unsigned short fid = 0;
                memcpy(&fid, (unsigned char *)na + NLA_HDRLEN, 2);
                fam = fid;
            }
            rem -= NLA_ALIGN(na->nla_len);
            na = (struct nlattr *)((unsigned char *)na + NLA_ALIGN(na->nla_len));
        }
    }
    if (fam < 0) { printf("no nl80211 family\n"); return 1; }

    if (argc > 2 && strcmp(argv[1], "addap") == 0) {
        unsigned wiphy = 0;
        unsigned iftype = NL80211_IFTYPE_AP;
        if (argc > 3) {
            wiphy = atoi(argv[3]);
        } else {
            nlsend(fd, fam, NL80211_CMD_GET_WIPHY, NLM_F_DUMP, 0);
            for (;;) {
                n = nlrecv(fd, rb, sizeof(rb));
                if (n <= 0) break;
                int done = 0;
                for (nlh = (struct nlmsghdr *)rb; NLMSG_OK(nlh, n); nlh = NLMSG_NEXT(nlh, n)) {
                    if (nlh->nlmsg_type == NLMSG_DONE) { done = 1; break; }
                    if (nlh->nlmsg_type == NLMSG_ERROR) { done = 1; break; }
                    if (wiphy) continue;
                    struct genlmsghdr *gh = (struct genlmsghdr *)NLMSG_DATA(nlh);
                    int rem = nlh->nlmsg_len - NLMSG_LENGTH(GENL_HDRLEN);
                    struct nlattr *na = (struct nlattr *)((unsigned char *)gh + GENL_HDRLEN);
                    while (rem >= (int)sizeof(struct nlattr)) {
                        if (na->nla_len < sizeof(struct nlattr) || na->nla_len > rem) break;
                        if (na->nla_type == NL80211_ATTR_WIPHY)
                            memcpy(&wiphy, (unsigned char *)na + NLA_HDRLEN, 4);
                        rem -= NLA_ALIGN(na->nla_len);
                        na = (struct nlattr *)((unsigned char *)na + NLA_ALIGN(na->nla_len));
                    }
                }
                if (done) break;
            }
        }
        printf("using wiphy %u\n", wiphy);
        int pl = 0;
        pl += put_attr(pay + pl, NL80211_ATTR_WIPHY, &wiphy, 4);
        pl += put_attr(pay + pl, NL80211_ATTR_IFNAME, argv[2], strlen(argv[2]) + 1);
        pl += put_attr(pay + pl, NL80211_ATTR_IFTYPE, &iftype, 4);
        nlsend(fd, fam, NL80211_CMD_NEW_INTERFACE, NLM_F_ACK, pl);
        n = nlrecv(fd, rb, sizeof(rb));
        nlh = (struct nlmsghdr *)rb;
        if (n > 0 && nlh->nlmsg_type == NLMSG_ERROR) {
            struct nlmsgerr *e = (struct nlmsgerr *)NLMSG_DATA(nlh);
            printf("NEW_INTERFACE ap err=%d (%s)\n", e->error, strerror(-e->error));
        } else {
            printf("NEW_INTERFACE ap ok\n");
        }
        return 0;
    }

    nlsend(fd, fam, NL80211_CMD_GET_WIPHY, NLM_F_DUMP, 0);
    for (;;) {
        n = nlrecv(fd, rb, sizeof(rb));
        if (n <= 0) break;
        int done = 0;
        for (nlh = (struct nlmsghdr *)rb; NLMSG_OK(nlh, n); nlh = NLMSG_NEXT(nlh, n)) {
            if (nlh->nlmsg_type == NLMSG_DONE) { done = 1; break; }
            if (nlh->nlmsg_type == NLMSG_ERROR) {
                struct nlmsgerr *e = (struct nlmsgerr *)NLMSG_DATA(nlh);
                printf("GET_WIPHY err=%d\n", e->error);
                done = 1;
                break;
            }
            struct genlmsghdr *gh = (struct genlmsghdr *)NLMSG_DATA(nlh);
            int rem = nlh->nlmsg_len - NLMSG_LENGTH(GENL_HDRLEN);
            struct nlattr *na = (struct nlattr *)((unsigned char *)gh + GENL_HDRLEN);
            while (rem >= (int)sizeof(struct nlattr)) {
                if (na->nla_len < sizeof(struct nlattr) || na->nla_len > rem) break;
                int alen = na->nla_len - NLA_HDRLEN;
                unsigned char *ad = (unsigned char *)na + NLA_HDRLEN;
                switch (na->nla_type) {
                case NL80211_ATTR_WIPHY:
                    printf("wiphy %u\n", *(unsigned *)ad);
                    break;
                case NL80211_ATTR_WIPHY_NAME:
                    printf("name %s\n", ad);
                    break;
                case NL80211_ATTR_SUPPORTED_IFTYPES:
                    walk_iftypes((struct nlattr *)ad, alen, "iftypes");
                    break;
                case NL80211_ATTR_INTERFACE_COMBINATIONS:
                    dump_combos((struct nlattr *)ad, alen);
                    break;
                }
                rem -= NLA_ALIGN(na->nla_len);
                na = (struct nlattr *)((unsigned char *)na + NLA_ALIGN(na->nla_len));
            }
        }
        if (done) break;
    }
    return 0;
}