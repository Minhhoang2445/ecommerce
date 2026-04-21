CREATE TABLE t_inventory (
    id bigint(20) NOT NULL AUTO_INCREMENT,
    sku_code varchar(255) default NULL,
    quantity int(11) default NULL,
    PRIMARY KEY (id)
);