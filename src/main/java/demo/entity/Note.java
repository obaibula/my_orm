package demo.entity;

import orm.annotation.Column;
import orm.annotation.Id;
import orm.annotation.Table;
import lombok.Getter;
import lombok.Setter;
import lombok.ToString;

@Table
@ToString
@Getter
@Setter
public class Note {
    @Id
    private Long id;

    @Column(name = "body")
    private String body;
}
